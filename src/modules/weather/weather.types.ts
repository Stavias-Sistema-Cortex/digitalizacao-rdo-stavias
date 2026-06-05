export type WeatherRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export interface CorridorRisk {
  km: number;
  riskLevel: WeatherRiskLevel;
  rainfallProbability: number;
  rainfallMm?: number;
  temperatureC?: number;
  windKmh?: number;
  message: string;
}
