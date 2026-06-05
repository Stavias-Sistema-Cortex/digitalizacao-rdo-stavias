import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { HealthModule } from './modules/health/health.module';
import { MaintenanceModule } from './modules/maintenance/maintenance.module';
import { RdoModule } from './modules/rdo/rdo.module';
import { SyncModule } from './modules/sync/sync.module';
import { WeatherModule } from './modules/weather/weather.module';
import { PrismaModule } from './prisma/prisma.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
    }),
    PrismaModule,
    HealthModule,
    RdoModule,
    SyncModule,
    WeatherModule,
    MaintenanceModule,
  ],
})
export class AppModule {}
