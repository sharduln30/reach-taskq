import { create } from "zustand";
import type { Job, JobStatus, JobStatusEvent } from "./types";

type LiveStatusMap = Record<string, { status: JobStatus; attempt: number; updatedAt: string }>;

interface LiveJobsState {
  liveStatus: LiveStatusMap;
  recentEvents: JobStatusEvent[];
  wsConnected: boolean;
  setWsConnected: (connected: boolean) => void;
  applyEvent: (event: JobStatusEvent) => void;
  mergeJobs: (jobs: Job[]) => Job[];
  reset: () => void;
}

const MAX_EVENTS = 200;

export const useLiveJobs = create<LiveJobsState>((set, get) => ({
  liveStatus: {},
  recentEvents: [],
  wsConnected: false,

  setWsConnected: (connected) => set({ wsConnected: connected }),

  applyEvent: (event) =>
    set((state) => {
      const next: LiveStatusMap = {
        ...state.liveStatus,
        [event.jobId]: {
          status: event.status,
          attempt: event.attempt,
          updatedAt: event.updatedAt,
        },
      };
      const events = [event, ...state.recentEvents].slice(0, MAX_EVENTS);
      return { liveStatus: next, recentEvents: events };
    }),

  mergeJobs: (jobs) => {
    const map = get().liveStatus;
    return jobs.map((j) => {
      const live = map[j.id];
      if (!live) return j;
      const liveTime = new Date(live.updatedAt).getTime();
      const jobTime = new Date(j.updatedAt).getTime();
      if (liveTime > jobTime) {
        return { ...j, status: live.status, attempt: live.attempt, updatedAt: live.updatedAt };
      }
      return j;
    });
  },

  reset: () => set({ liveStatus: {}, recentEvents: [], wsConnected: false }),
}));
