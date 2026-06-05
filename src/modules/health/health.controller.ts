import { Controller, Get } from '@nestjs/common';

@Controller('health')
export class HealthController {
  @Get()
  check() {
    return {
      status: 'ok',
      service: 'digitalizacao-rdo-api',
      timestamp: new Date().toISOString(),
    };
  }
}
