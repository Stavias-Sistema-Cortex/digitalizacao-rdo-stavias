import { Type } from 'class-transformer';
import {
  IsArray,
  IsBoolean,
  IsDateString,
  IsInt,
  IsNumber,
  IsOptional,
  IsString,
  Min,
  ValidateNested,
} from 'class-validator';

export class CreateLaborEntryDto {
  @IsString()
  role!: string;

  @IsInt()
  @Min(0)
  quantity!: number;

  @IsOptional()
  @IsNumber()
  @Min(0)
  hours?: number;
}

export class CreateEquipmentEntryDto {
  @IsString()
  equipmentCode!: string;

  @IsOptional()
  @IsString()
  description?: string;

  @IsOptional()
  @IsDateString()
  startedAt?: string;

  @IsOptional()
  @IsDateString()
  stoppedAt?: string;

  @IsOptional()
  @IsInt()
  @Min(0)
  downtimeMinutes?: number;

  @IsOptional()
  @IsBoolean()
  broken?: boolean;
}

export class CreateProductionEntryDto {
  @IsOptional()
  @IsString()
  serviceCode?: string;

  @IsString()
  description!: string;

  @IsString()
  unit!: string;

  @IsNumber()
  @Min(0)
  quantity!: number;

  @IsOptional()
  @IsNumber()
  startKm?: number;

  @IsOptional()
  @IsNumber()
  endKm?: number;
}

export class CreateOccurrenceDto {
  @IsString()
  type!: string;

  @IsString()
  description!: string;

  @IsOptional()
  @IsString()
  severity?: string;

  @IsOptional()
  @IsBoolean()
  actionRequired?: boolean;

  @IsOptional()
  @IsString()
  assignedTo?: string;
}

export class CreateWeatherRecordDto {
  @IsOptional()
  @IsString()
  source?: string;

  @IsOptional()
  @IsNumber()
  temperatureC?: number;

  @IsOptional()
  @IsInt()
  @Min(0)
  rainfallProbability?: number;

  @IsOptional()
  @IsNumber()
  @Min(0)
  rainfallMm?: number;

  @IsOptional()
  @IsNumber()
  @Min(0)
  windKmh?: number;

  @IsOptional()
  @IsInt()
  @Min(0)
  humidity?: number;
}

export class CreateRdoDto {
  @IsString()
  projectId!: string;

  @IsOptional()
  @IsString()
  workFrontId?: string;

  @IsOptional()
  @IsString()
  deviceId?: string;

  @IsDateString()
  reportDate!: string;

  @IsString()
  createdByName!: string;

  @IsOptional()
  @IsString()
  notes?: string;

  @IsOptional()
  @ValidateNested()
  @Type(() => CreateWeatherRecordDto)
  weather?: CreateWeatherRecordDto;

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => CreateLaborEntryDto)
  laborEntries: CreateLaborEntryDto[] = [];

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => CreateEquipmentEntryDto)
  equipmentEntries: CreateEquipmentEntryDto[] = [];

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => CreateProductionEntryDto)
  productionEntries: CreateProductionEntryDto[] = [];

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => CreateOccurrenceDto)
  occurrences: CreateOccurrenceDto[] = [];
}
