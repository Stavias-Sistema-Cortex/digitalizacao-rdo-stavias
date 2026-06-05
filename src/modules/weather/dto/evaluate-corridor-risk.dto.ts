import { Type } from 'class-transformer';
import {
  IsArray,
  IsInt,
  IsNumber,
  IsOptional,
  Max,
  Min,
  ValidateNested,
} from 'class-validator';

export class ForecastPointDto {
  @IsNumber()
  km!: number;

  @IsInt()
  @Min(0)
  @Max(100)
  rainfallProbability!: number;

  @IsOptional()
  @IsNumber()
  @Min(0)
  rainfallMm?: number;

  @IsOptional()
  @IsNumber()
  temperatureC?: number;

  @IsOptional()
  @IsNumber()
  @Min(0)
  windKmh?: number;
}

export class EvaluateCorridorRiskDto {
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => ForecastPointDto)
  forecasts!: ForecastPointDto[];
}
