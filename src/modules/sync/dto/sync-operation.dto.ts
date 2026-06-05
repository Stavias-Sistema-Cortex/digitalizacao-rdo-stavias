import { Type } from 'class-transformer';
import {
  IsArray,
  IsDateString,
  IsIn,
  IsInt,
  IsObject,
  IsOptional,
  IsString,
  ValidateNested,
} from 'class-validator';
import {
  SYNC_ENTITY_TYPES,
  SYNC_OPERATION_TYPES,
  SyncEntityType,
  SyncOperationType,
} from '../sync.types';

export class SyncOperationDto {
  @IsString()
  operationId!: string;

  @IsIn(SYNC_ENTITY_TYPES)
  entityType!: SyncEntityType;

  @IsString()
  entityId!: string;

  @IsIn(SYNC_OPERATION_TYPES)
  operationType!: SyncOperationType;

  @IsObject()
  payload!: Record<string, unknown>;

  @IsDateString()
  occurredAt!: string;

  @IsOptional()
  @IsInt()
  baseVersion?: number;
}

export class PushSyncDto {
  @IsString()
  deviceId!: string;

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => SyncOperationDto)
  operations!: SyncOperationDto[];
}
