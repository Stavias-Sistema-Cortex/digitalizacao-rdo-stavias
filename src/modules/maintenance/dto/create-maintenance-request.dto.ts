import { IsIn, IsOptional, IsString } from 'class-validator';
import {
  MAINTENANCE_PRIORITIES,
  MaintenancePriority,
} from '../maintenance.types';

export class CreateMaintenanceRequestDto {
  @IsOptional()
  @IsString()
  rdoId?: string;

  @IsString()
  equipmentCode!: string;

  @IsString()
  reportedByName!: string;

  @IsString()
  problemDescription!: string;

  @IsIn(MAINTENANCE_PRIORITIES)
  priority!: MaintenancePriority;
}
