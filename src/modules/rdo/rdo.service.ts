import { Injectable, NotFoundException } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { CreateRdoDto } from './dto/create-rdo.dto';
import { UpdateRdoStatusDto } from './dto/update-rdo-status.dto';
import { RdoRecord } from './rdo.types';

@Injectable()
export class RdoService {
  private readonly rdos = new Map<string, RdoRecord>();

  create(dto: CreateRdoDto): RdoRecord {
    const now = new Date().toISOString();
    const rdo: RdoRecord = {
      ...dto,
      id: randomUUID(),
      status: 'DRAFT',
      serverVersion: 1,
      createdAt: now,
      updatedAt: now,
      laborEntries: dto.laborEntries ?? [],
      equipmentEntries: dto.equipmentEntries ?? [],
      productionEntries: dto.productionEntries ?? [],
      occurrences: dto.occurrences ?? [],
    };

    this.rdos.set(rdo.id, rdo);
    return rdo;
  }

  findAll(): RdoRecord[] {
    return Array.from(this.rdos.values()).sort((first, second) =>
      second.reportDate.localeCompare(first.reportDate),
    );
  }

  findOne(id: string): RdoRecord {
    const rdo = this.rdos.get(id);

    if (!rdo) {
      throw new NotFoundException(`RDO ${id} não encontrado`);
    }

    return rdo;
  }

  updateStatus(id: string, dto: UpdateRdoStatusDto): RdoRecord {
    const current = this.findOne(id);
    const now = new Date().toISOString();
    const updated: RdoRecord = {
      ...current,
      status: dto.status,
      approvedByName: dto.approvedByName ?? current.approvedByName,
      submittedAt:
        dto.status === 'SENT_TO_TECHNICAL_ROOM'
          ? now
          : current.submittedAt,
      approvedAt: dto.status === 'APPROVED' ? now : current.approvedAt,
      serverVersion: current.serverVersion + 1,
      updatedAt: now,
    };

    this.rdos.set(id, updated);
    return updated;
  }
}
