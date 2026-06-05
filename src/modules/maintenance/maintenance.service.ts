import { Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { CreateMaintenanceRequestDto } from './dto/create-maintenance-request.dto';
import { MaintenanceRequestRecord } from './maintenance.types';

@Injectable()
export class MaintenanceService {
  private readonly requests = new Map<string, MaintenanceRequestRecord>();

  create(dto: CreateMaintenanceRequestDto): MaintenanceRequestRecord {
    const now = new Date().toISOString();
    const request: MaintenanceRequestRecord = {
      ...dto,
      id: randomUUID(),
      status: 'OPEN',
      createdAt: now,
      updatedAt: now,
    };

    this.requests.set(request.id, request);
    return request;
  }

  findAll(): MaintenanceRequestRecord[] {
    return Array.from(this.requests.values()).sort((first, second) =>
      second.createdAt.localeCompare(first.createdAt),
    );
  }
}
