import { Body, Controller, Get, Post } from '@nestjs/common';
import { CreateMaintenanceRequestDto } from './dto/create-maintenance-request.dto';
import { MaintenanceService } from './maintenance.service';

@Controller('maintenance')
export class MaintenanceController {
  constructor(private readonly maintenanceService: MaintenanceService) {}

  @Post('requests')
  create(@Body() dto: CreateMaintenanceRequestDto) {
    return this.maintenanceService.create(dto);
  }

  @Get('requests')
  findAll() {
    return this.maintenanceService.findAll();
  }
}
