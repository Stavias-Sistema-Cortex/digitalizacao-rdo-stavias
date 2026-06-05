import { Body, Controller, Get, Post, Query } from '@nestjs/common';
import { PushSyncDto } from './dto/sync-operation.dto';
import { SyncService } from './sync.service';

@Controller('sync')
export class SyncController {
  constructor(private readonly syncService: SyncService) {}

  @Post('push')
  push(@Body() dto: PushSyncDto) {
    return this.syncService.push(dto);
  }

  @Get('pull')
  pull(@Query('since') since?: string) {
    return this.syncService.pull(since);
  }
}
