import { Module } from '@nestjs/common';
import { RdoController } from './rdo.controller';
import { RdoService } from './rdo.service';

@Module({
  controllers: [RdoController],
  providers: [RdoService],
  exports: [RdoService],
})
export class RdoModule {}
