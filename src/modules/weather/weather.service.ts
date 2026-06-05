import { Injectable } from '@nestjs/common';
import { EvaluateCorridorRiskDto } from './dto/evaluate-corridor-risk.dto';
import { CorridorRisk, WeatherRiskLevel } from './weather.types';

@Injectable()
export class WeatherService {
  evaluateCorridorRisk(dto: EvaluateCorridorRiskDto): CorridorRisk[] {
    return dto.forecasts
      .map((forecast) => {
        const riskLevel = this.classifyRisk(
          forecast.rainfallProbability,
          forecast.rainfallMm,
        );

        return {
          ...forecast,
          riskLevel,
          message: this.buildMessage(forecast.km, riskLevel),
        };
      })
      .sort((first, second) => first.km - second.km);
  }

  private classifyRisk(
    rainfallProbability: number,
    rainfallMm = 0,
  ): WeatherRiskLevel {
    if (rainfallProbability >= 70 || rainfallMm >= 10) {
      return 'HIGH';
    }

    if (rainfallProbability >= 40 || rainfallMm >= 2) {
      return 'MEDIUM';
    }

    return 'LOW';
  }

  private buildMessage(km: number, riskLevel: WeatherRiskLevel): string {
    if (riskLevel === 'HIGH') {
      return `Alto risco de chuva no km ${km}. Replanejar atividades sensíveis.`;
    }

    if (riskLevel === 'MEDIUM') {
      return `Atenção no km ${km}. Monitorar antes de iniciar atividades críticas.`;
    }

    return `Baixo risco climático no km ${km}.`;
  }
}
