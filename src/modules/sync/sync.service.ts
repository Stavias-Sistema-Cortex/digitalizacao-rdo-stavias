import { Injectable } from '@nestjs/common';
import { PushSyncDto } from './dto/sync-operation.dto';
import { SyncAcceptedEvent, SyncPullResult, SyncPushResult } from './sync.types';

@Injectable()
export class SyncService {
  private readonly events: SyncAcceptedEvent[] = [];

  push(dto: PushSyncDto): SyncPushResult {
    const serverTime = new Date().toISOString();

    const accepted = dto.operations.map((operation) => {
      const event: SyncAcceptedEvent = {
        ...operation,
        receivedAt: serverTime,
        serverVersion: (operation.baseVersion ?? 0) + 1,
      };

      this.events.push(event);

      return {
        operationId: operation.operationId,
        entityType: operation.entityType,
        entityId: operation.entityId,
        serverVersion: event.serverVersion,
      };
    });

    return {
      accepted,
      conflicts: [],
      serverTime,
    };
  }

  pull(since?: string): SyncPullResult {
    const sinceTime = since ? Date.parse(since) : 0;
    const changes = this.events.filter((event) => {
      return Date.parse(event.receivedAt) > sinceTime;
    });

    return {
      changes,
      serverTime: new Date().toISOString(),
    };
  }
}
