export const MAINTENANCE_PRIORITIES = [
  'LOW',
  'MEDIUM',
  'HIGH',
  'CRITICAL',
] as const;

export const MAINTENANCE_STATUSES = [
  'OPEN',
  'IN_PROGRESS',
  'RESOLVED',
  'CANCELED',
] as const;

export type MaintenancePriority = (typeof MAINTENANCE_PRIORITIES)[number];
export type MaintenanceStatus = (typeof MAINTENANCE_STATUSES)[number];

export interface MaintenanceRequestRecord {
  id: string;
  rdoId?: string;
  equipmentCode: string;
  reportedByName: string;
  problemDescription: string;
  priority: MaintenancePriority;
  status: MaintenanceStatus;
  createdAt: string;
  updatedAt: string;
}
