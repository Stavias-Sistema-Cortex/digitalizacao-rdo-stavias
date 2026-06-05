import type {
  CreateEquipmentEntryDto,
  CreateLaborEntryDto,
  CreateOccurrenceDto,
  CreateProductionEntryDto,
  CreateRdoDto,
  CreateWeatherRecordDto,
} from './dto/create-rdo.dto';

export const RDO_STATUSES = [
  'DRAFT',
  'SENT_TO_TECHNICAL_ROOM',
  'IN_VALIDATION',
  'APPROVED',
  'REJECTED',
] as const;

export type RdoStatus = (typeof RDO_STATUSES)[number];

export interface RdoRecord extends CreateRdoDto {
  id: string;
  status: RdoStatus;
  serverVersion: number;
  submittedAt?: string;
  approvedAt?: string;
  approvedByName?: string;
  createdAt: string;
  updatedAt: string;
  weather?: CreateWeatherRecordDto;
  laborEntries: CreateLaborEntryDto[];
  equipmentEntries: CreateEquipmentEntryDto[];
  productionEntries: CreateProductionEntryDto[];
  occurrences: CreateOccurrenceDto[];
}
