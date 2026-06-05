import { IsIn, IsOptional, IsString } from 'class-validator';
import { RDO_STATUSES, RdoStatus } from '../rdo.types';

export class UpdateRdoStatusDto {
  @IsIn(RDO_STATUSES)
  status!: RdoStatus;

  @IsOptional()
  @IsString()
  approvedByName?: string;

  @IsOptional()
  @IsString()
  reason?: string;
}
