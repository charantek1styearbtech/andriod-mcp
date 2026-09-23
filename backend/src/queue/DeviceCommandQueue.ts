import { McpJob } from '../types/protocol.js';
import { deviceRegistry } from '../devices/DeviceRegistry.js';

export type JobDispatcher = (job: McpJob) => Promise<void> | void;

export class DeviceCommandQueue {
  // Map of deviceId -> FIFO array of queued jobs
  private queues: Map<string, McpJob[]> = new Map();
  // Map of deviceId -> currently active/running job
  private activeJobs: Map<string, McpJob | null> = new Map();
  // Dispatch callback injected by JobManager
  private dispatcher?: JobDispatcher;

  constructor() {
    // Listen for device disconnections to abort active/queued jobs
    deviceRegistry.onDisconnect((deviceId) => {
      this.handleDeviceDisconnect(deviceId);
    });
  }

  public setDispatcher(dispatcher: JobDispatcher): void {
    this.dispatcher = dispatcher;
  }

  /**
   * Enqueues a job for a specific device. If the device is currently idle,
   * it immediately locks the device and dispatches the job.
   */
  public enqueue(job: McpJob): void {
    const { deviceId } = job;

    if (!this.queues.has(deviceId)) {
      this.queues.set(deviceId, []);
    }

    const queue = this.queues.get(deviceId)!;
    queue.push(job);
    job.status = 'QUEUED';

    console.log(`[DeviceCommandQueue] Enqueued job ${job.requestId} for device ${deviceId}. Queue length: ${queue.length}`);

    // If no active job on this device, process next
    if (!this.activeJobs.get(deviceId)) {
      this.processNext(deviceId);
    }
  }

  /**
   * Process the next job in the queue for a given device.
   */
  public processNext(deviceId: string): void {
    const queue = this.queues.get(deviceId);
    if (!queue || queue.length === 0) {
      this.activeJobs.set(deviceId, null);
      deviceRegistry.setDeviceState(deviceId, 'ONLINE');
      return;
    }

    // Dequeue next job
    const nextJob = queue.shift()!;
    this.activeJobs.set(deviceId, nextJob);
    nextJob.status = 'RUNNING';
    nextJob.startedAt = Date.now();
    deviceRegistry.setDeviceState(deviceId, 'BUSY');

    console.log(`[DeviceCommandQueue] Dispatching job ${nextJob.requestId} to device ${deviceId} (Remaining in queue: ${queue.length})`);

    if (this.dispatcher) {
      try {
        this.dispatcher(nextJob);
      } catch (err) {
        console.error(`[DeviceCommandQueue] Error dispatching job ${nextJob.requestId}:`, err);
      }
    }
  }

  /**
   * Called when a job completes, times out, or fails, to release the device lock
   * and advance the queue.
   */
  public release(deviceId: string, requestId: string): void {
    const current = this.activeJobs.get(deviceId);
    if (current && current.requestId === requestId) {
      console.log(`[DeviceCommandQueue] Releasing lock for device ${deviceId} from job ${requestId}`);
      this.activeJobs.set(deviceId, null);
      // Process next queued command on this device
      this.processNext(deviceId);
    } else {
      // If it wasn't the active job, check if it was sitting in the queue and remove it
      const queue = this.queues.get(deviceId);
      if (queue) {
        const idx = queue.findIndex((j) => j.requestId === requestId);
        if (idx !== -1) {
          queue.splice(idx, 1);
          console.log(`[DeviceCommandQueue] Removed unstarted job ${requestId} from queue for device ${deviceId}`);
        }
      }
    }
  }

  /**
   * Get the currently executing job on a device.
   */
  public getActiveJob(deviceId: string): McpJob | null {
    return this.activeJobs.get(deviceId) || null;
  }

  /**
   * Get queue length for a device.
   */
  public getQueueLength(deviceId: string): number {
    return this.queues.get(deviceId)?.length || 0;
  }

  private handleDeviceDisconnect(deviceId: string): void {
    // Release active job
    const active = this.activeJobs.get(deviceId);
    if (active) {
      console.warn(`[DeviceCommandQueue] Device ${deviceId} disconnected while running job ${active.requestId}`);
      this.activeJobs.set(deviceId, null);
    }

    // Clear queue
    const queue = this.queues.get(deviceId);
    if (queue && queue.length > 0) {
      console.warn(`[DeviceCommandQueue] Clearing ${queue.length} queued jobs for disconnected device ${deviceId}`);
      this.queues.set(deviceId, []);
    }
  }
}

export const deviceCommandQueue = new DeviceCommandQueue();
