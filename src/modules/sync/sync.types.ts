import type { SyncOperationDto } from './dto/sync-operation.dto';

export const SYNC_ENTITY_TYPES = [
  'RDO',
  'MAINTENANCE_REQUEST',
  'WEATHER_SNAPSHOT',
] as const;

export const SYNC_OPERATION_TYPES = ['CREATE', 'UPDATE', 'DELETE'] as const;

export type SyncEntityType = (typeof SYNC_ENTITY_TYPES)[number];
export type SyncOperationType = (typeof SYNC_OPERATION_TYPES)[number];

export interface SyncAcceptedEvent extends SyncOperationDto {
  receivedAt: string;
  serverVersion: number;
}

export interface SyncPushResult {
  accepted: Array<{
    operationId: string;
    entityType: SyncEntityType;
    entityId: string;
    serverVersion: number;
  }>;
  conflicts: Array<{
    operationId: string;
    reason: string;
  }>;
  serverTime: string;
}

export interface SyncPullResult {
  changes: SyncAcceptedEvent[];
  serverTime: string;
}
