import { Body, Controller, Get, Param, Patch, Post } from '@nestjs/common';
import { CreateRdoDto } from './dto/create-rdo.dto';
import { UpdateRdoStatusDto } from './dto/update-rdo-status.dto';
import { RdoService } from './rdo.service';

@Controller('rdos')
export class RdoController {
  constructor(private readonly rdoService: RdoService) {}

  @Post()
  create(@Body() dto: CreateRdoDto) {
    return this.rdoService.create(dto);
  }

  @Get()
  findAll() {
    return this.rdoService.findAll();
  }

  @Get(':id')
  findOne(@Param('id') id: string) {
    return this.rdoService.findOne(id);
  }

  @Patch(':id/status')
  updateStatus(@Param('id') id: string, @Body() dto: UpdateRdoStatusDto) {
    return this.rdoService.updateStatus(id, dto);
  }
}
