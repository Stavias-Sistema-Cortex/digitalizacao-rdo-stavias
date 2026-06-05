import { Body, Controller, Post } from '@nestjs/common';
import { EvaluateCorridorRiskDto } from './dto/evaluate-corridor-risk.dto';
import { WeatherService } from './weather.service';

@Controller('weather')
export class WeatherController {
  constructor(private readonly weatherService: WeatherService) {}

  @Post('corridor-risk')
  evaluateCorridorRisk(@Body() dto: EvaluateCorridorRiskDto) {
    return this.weatherService.evaluateCorridorRisk(dto);
  }
}
