import { v4 as uuidv4 } from 'uuid';
import { McpJob } from '../types/protocol.js';
import { deviceRegistry } from '../devices/DeviceRegistry.js';
import { deviceCommandQueue } from '../queue/DeviceCommandQueue.js';
import { redisClusterManager, CrossInstanceJobPayload } from '../db/redis.js';
import { mongoDatabase } from '../db/mongo.js';

interface PendingJobState {
  job: McpJob;
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
  timer?: NodeJS.Timeout;
}

export class JobManager {
  // Map of requestId -> PendingJobState
  private activePendingJobs: Map<string, PendingJobState> = new Map();

  constructor() {
    // Wire up queue dispatcher
    deviceCommandQueue.setDispatcher((job) => this.dispatchJobToDevice(job));

    // Listen for device disconnection to fail any jobs associated with that device
    deviceRegistry.onDisconnect((deviceId) => {
      this.handleDeviceDisconnect(deviceId);
    });

    // Listen for cross-instance jobs dispatched via Redis Pub/Sub
    redisClusterManager.listenForInstanceJobs(async (jobPayload) => {
      await this.handleRemoteJob(jobPayload);
    });
  }

  /**
   * Submit an MCP action job for execution on a specific device.
   * Checks both local WebSockets and remote cluster instances via Redis Pub/Sub.
   */
  public async submitJob(
    userId: string,
    deviceId: string,
    action: string,
    params: Record<string, unknown> = {},
    timeoutMs: number = 30000
  ): Promise<unknown> {
    const requestId = `req_${uuidv4()}`;
    const startTime = Date.now();

    // 1. Check if device is connected to a different instance in the cluster
    const targetInstance = await deviceRegistry.findDeviceInstance(deviceId);

    if (targetInstance && targetInstance !== redisClusterManager.instanceId) {
      console.log(
        `[JobManager] Device '${deviceId}' is connected on remote instance '${targetInstance}'. Dispatching via Redis Pub/Sub [${requestId}]...`
      );

      try {
        await redisClusterManager.publishJobToInstance(targetInstance, {
          requestId,
          userId,
          deviceId,
          action,
          params,
          originInstanceId: redisClusterManager.instanceId,
          timeoutMs,
        });

        // Await execution result from the remote instance
        const resultPayload = await redisClusterManager.subscribeToJobResult(requestId, timeoutMs);
        const durationMs = Date.now() - startTime;

        // Record audit log
        mongoDatabase.recordAuditLog({
          requestId,
          userId,
          deviceId,
          action,
          params,
          success: resultPayload.success,
          durationMs,
          error: resultPayload.error,
        });

        if (!resultPayload.success) {
          throw new Error(resultPayload.error || 'Cross-instance action failed on device');
        }

        return resultPayload.result;
      } catch (err) {
        const durationMs = Date.now() - startTime;
        mongoDatabase.recordAuditLog({
          requestId,
          userId,
          deviceId,
          action,
          params,
          success: false,
          durationMs,
          error: (err as Error).message,
        });
        throw err;
      }
    }

    // 2. Verify local device is online
    if (!deviceRegistry.isOnline(deviceId)) {
      throw new Error(`Device '${deviceId}' is currently OFFLINE or not connected to any cluster node.`);
    }

    const job: McpJob = {
      requestId,
      userId,
      deviceId,
      action,
      params,
      status: 'QUEUED',
      createdAt: Date.now(),
      timeoutMs,
    };

    return new Promise((resolve, reject) => {
      this.activePendingJobs.set(requestId, {
        job,
        resolve: (val) => {
          const durationMs = Date.now() - startTime;
          mongoDatabase.recordAuditLog({
            requestId,
            userId,
            deviceId,
            action,
            params,
            success: true,
            durationMs,
          });
          resolve(val);
        },
        reject: (err) => {
          const durationMs = Date.now() - startTime;
          mongoDatabase.recordAuditLog({
            requestId,
            userId,
            deviceId,
            action,
            params,
            success: false,
            durationMs,
            error: err instanceof Error ? err.message : String(err),
          });
          reject(err);
        },
      });

      // Enqueue into per-device serialized queue
      deviceCommandQueue.enqueue(job);
    });
  }

  /**
   * Handle an incoming job forwarded from another cluster node via Redis Pub/Sub.
   */
  private async handleRemoteJob(remoteJob: CrossInstanceJobPayload): Promise<void> {
    const { requestId, userId, deviceId, action, params, originInstanceId, timeoutMs } = remoteJob;
    console.log(`[JobManager] Received remote job [${requestId}] for local device '${deviceId}' from instance '${originInstanceId}'`);

    if (!deviceRegistry.isOnline(deviceId)) {
      await redisClusterManager.publishJobResult(requestId, {
        requestId,
        deviceId,
        success: false,
        error: `Device '${deviceId}' is offline on instance '${redisClusterManager.instanceId}'`,
      });
      return;
    }

    const job: McpJob = {
      requestId,
      userId,
      deviceId,
      action,
      params,
      status: 'QUEUED',
      createdAt: Date.now(),
      timeoutMs,
      originInstanceId,
    };

    this.activePendingJobs.set(requestId, {
      job,
      resolve: async (result) => {
        await redisClusterManager.publishJobResult(requestId, {
          requestId,
          deviceId,
          success: true,
          result,
        });
      },
      reject: async (err) => {
        await redisClusterManager.publishJobResult(requestId, {
          requestId,
          deviceId,
          success: false,
          error: err instanceof Error ? err.message : String(err),
        });
      },
    });

    deviceCommandQueue.enqueue(job);
  }

  /**
   * Dispatches the job over the device's persistent WebSocket connection.
   */
  private dispatchJobToDevice(job: McpJob): void {
    const pending = this.activePendingJobs.get(job.requestId);
    if (!pending) {
      console.warn(`[JobManager] Cannot dispatch job ${job.requestId}: not found in pending map.`);
      deviceCommandQueue.release(job.deviceId, job.requestId);
      return;
    }

    const sent = deviceRegistry.sendToDevice(job.deviceId, {
      type: 'EXECUTE_ACTION',
      requestId: job.requestId,
      action: job.action,
      params: job.params,
      timeoutMs: job.timeoutMs,
    });

    if (!sent) {
      job.status = 'FAILED';
      job.error = 'Failed to send command to device socket';
      pending.reject(new Error(job.error));
      this.activePendingJobs.delete(job.requestId);
      deviceCommandQueue.release(job.deviceId, job.requestId);
      return;
    }

    job.status = 'SENT';

    // Start execution timeout
    pending.timer = setTimeout(() => {
      this.handleJobTimeout(job.requestId);
    }, job.timeoutMs);
  }

  /**
   * Handle ACK from device (e.g. action started)
   */
  public handleActionAck(requestId: string, status: 'RECEIVED' | 'RUNNING'): void {
    const pending = this.activePendingJobs.get(requestId);
    if (pending) {
      pending.job.status = 'RUNNING';
      console.log(`[JobManager] Device ACK for job ${requestId}: ${status}`);
    }
  }

  /**
   * Handle final execution result from device.
   */
  public handleActionResult(
    requestId: string,
    success: boolean,
    result?: unknown,
    error?: string
  ): void {
    const pending = this.activePendingJobs.get(requestId);
    if (!pending) {
      console.warn(`[JobManager] Received result for unknown or already finished job: ${requestId}`);
      return;
    }

    // Clear timeout timer
    if (pending.timer) {
      clearTimeout(pending.timer);
    }

    const { job, resolve, reject } = pending;
    job.completedAt = Date.now();
    this.activePendingJobs.delete(requestId);

    // Release device lock and process next job in device queue
    deviceCommandQueue.release(job.deviceId, requestId);

    if (success) {
      job.status = 'COMPLETED';
      job.result = result;
      console.log(`[JobManager] Job ${requestId} completed successfully on device ${job.deviceId}`);
      resolve(result);
    } else {
      job.status = 'FAILED';
      job.error = error || 'Action execution failed on device';
      console.error(`[JobManager] Job ${requestId} failed on device ${job.deviceId}: ${job.error}`);
      reject(new Error(job.error));
    }
  }

  /**
   * Timeout handler
   */
  private handleJobTimeout(requestId: string): void {
    const pending = this.activePendingJobs.get(requestId);
    if (!pending) return;

    const { job, reject } = pending;
    console.error(`[JobManager] Job ${requestId} timed out after ${job.timeoutMs}ms on device ${job.deviceId}`);

    job.status = 'TIMEOUT';
    job.error = `Command timed out after ${job.timeoutMs}ms`;
    this.activePendingJobs.delete(requestId);

    // Send cancellation message to phone
    deviceRegistry.sendToDevice(job.deviceId, {
      type: 'CANCEL_ACTION',
      requestId,
    });

    // Release device lock
    deviceCommandQueue.release(job.deviceId, requestId);

    reject(new Error(job.error));
  }

  /**
   * Handle device disconnect event
   */
  private handleDeviceDisconnect(deviceId: string): void {
    for (const [requestId, pending] of this.activePendingJobs.entries()) {
      if (pending.job.deviceId === deviceId) {
        if (pending.timer) {
          clearTimeout(pending.timer);
        }
        pending.job.status = 'FAILED';
        pending.job.error = `Device '${deviceId}' disconnected while executing command.`;
        pending.reject(new Error(pending.job.error));
        this.activePendingJobs.delete(requestId);
      }
    }
  }

  public getJob(requestId: string): McpJob | undefined {
    return this.activePendingJobs.get(requestId)?.job;
  }
}

export const jobManager = new JobManager();
