// TypeScript mirror of the supervisor's Dtos.java. Instants are ISO-8601 UTC strings.

export type Health = 'OPERATIONAL' | 'DEGRADED' | 'FAILED' | 'UNKNOWN';
export type ServiceStatus = 'OK' | 'DEGRADED' | 'FAILED' | 'UNKNOWN';
export type Severity = 'CRITICAL' | 'MAJOR' | 'MINOR' | 'WARNING' | 'INDETERMINATE' | 'CLEARED';
export type AlarmState = 'ACTIVE_UNACK' | 'ACTIVE_ACK' | 'CLEARED_UNACK' | 'CLOSED';

export type Values = Record<string, unknown>;
export type Tables = Record<string, Record<string, unknown>[]>;

export interface BoardDto {
  centre: string;
  serverTime: string;
  lastEventId: number;
  services: ServiceDto[];
  equipment: EquipmentDto[];
  alarms: AlarmDto[];
  flood: FloodDto;
}

export interface FloodDto {
  active: boolean;
  since: string | null;
}

export interface ServiceDto {
  id: string;
  name: string;
  status: ServiceStatus;
  bestCase: string;
  redundancyLost: boolean;
  reasons: string[];
  since: string;
}

export interface EquipmentDto {
  id: string;
  name: string;
  type: string;
  group: string;
  pair: string | null;
  health: Health;
  unknownReason: string | null;
  maintenance: boolean;
  role: string | null;
  healthSince: string;
  contact: ContactDto;
  values: Values;
  tables: Tables;
  /** When the shown values were sampled; set on the replay board only, null live. */
  sampledAt: string | null;
}

export interface ContactDto {
  reachable: boolean;
  lastContactAt: string | null;
  lostAt: string | null;
  lastPollAt: string | null;
  consecutiveFailures: number;
  pollIntervalMs: number;
}

export interface CorrelationDto {
  causeId: string;
  causeEquipmentId: string;
  causeCode: number;
  rule: 'SENSOR_INPUT' | 'SWITCHOVER' | 'SAME_UNIT';
}

export interface AlarmDto {
  id: string;
  equipmentId: string;
  code: number;
  instance: number;
  instanceName: string;
  severity: Severity;
  text: string;
  source: string;
  state: AlarmState;
  suppressed: boolean;
  unconfirmed: boolean;
  raisedAt: string;
  ackedAt: string | null;
  ackedBy: string | null;
  ackComment: string | null;
  clearedAt: string | null;
  correlation: CorrelationDto | null;
}

export interface EquipmentDetailDto {
  equipment: EquipmentDto;
  recentEvents: EventSummaryDto[];
}

export interface EventSummaryDto {
  id: number;
  occurredAt: string;
  type: string;
  summary: string;
}

export interface PageDto<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface AckRequest {
  comment: string | null;
}

export interface AckManyRequest {
  ids: string[];
  comment: string | null;
}

export interface AckResultDto {
  acknowledged: AlarmDto[];
  notFound: string[];
}

export interface TelemetryDto {
  equipmentId: string;
  lastPollAt: string;
  consecutiveFailures: number;
  values: Values;
  tables: Tables;
  serverTime: string;
}

export interface SupervisorEventDto {
  type: 'SUPERVISOR_STARTED' | 'SUPERVISOR_HEARTBEAT' | 'SUPERVISOR_STOPPING';
  occurredAt: string;
}

export type StreamMessage =
  | { kind: 'equipment'; id: number; data: EquipmentDto }
  | { kind: 'service'; id: number; data: ServiceDto }
  | { kind: 'alarm'; id: number; data: AlarmDto }
  | { kind: 'supervisor'; id: number; data: SupervisorEventDto }
  | { kind: 'flood'; id: number; data: FloodDto }
  | { kind: 'telemetry'; data: TelemetryDto }
  | { kind: 'heartbeat'; data: { serverTime: string } }
  | { kind: 'resync' }
  | { kind: 'open' }
  | { kind: 'error' };

// --- replay (history module) ------------------------------------------------------------------------------------

export interface ReplayBoardDto {
  at: string;
  noData: boolean;
  gapThresholdSeconds: number;
  /** The newest non-sample event at or before {@code at}; null when there is none. */
  lastRecordedAt: string | null;
  lastRecordedType: string | null;
  board: BoardDto;
}

/** The replay-only message kind {@code sample}; the live stream never carries it. */
export interface SampleDto {
  equipmentId: string;
  observedAt: string;
  values: Values;
  tables: Tables;
}

/** {@code kind} is a live kind, or the replay-only {@code sample} or {@code command}. */
export interface ReplayMessageDto {
  kind: string;
  data: unknown;
}

export interface ReplayEventDto {
  id: number;
  occurredAt: string;
  type: string;
  equipmentId: string | null;
  actor: string | null;
  summary: string;
  message: ReplayMessageDto;
}

export interface ReplayEventsDto {
  events: ReplayEventDto[];
  more: boolean;
}

// --- sign-in and accounts (security module) ---------------------------------------------------------------------

export interface UserDto {
  username: string;
  displayName: string;
  roles: string[];
}

export interface DemoAccount {
  username: string;
  role: string;
  password: string;
}

export interface AuthConfigDto {
  demoMode: boolean;
  registrationEnabled: boolean;
  demoAccounts: DemoAccount[];
}

export interface RegisterRequest {
  username: string;
  displayName: string;
  password: string;
}

/** What-if maintenance report; {@code basis} says what the verdicts rest on. */
export interface ImpactDto {
  asOf: string;
  basis: string;
  plan: PlanDto;
  singlePoints: SinglePointDto[];
  outOfService: OutOfServiceDto[];
  unknownUnits: string[];
}

export interface PlanDto {
  units: string[];
  verdict: string;
  unconfirmed: boolean;
  services: ServiceChangeDto[];
  notes: string[];
}

/** The best cases are null unless the side is UNKNOWN. */
export interface ServiceChangeDto {
  id: string;
  name: string;
  verdict: string;
  before: string;
  beforeBestCase: string | null;
  after: string;
  afterBestCase: string | null;
  redundancyLostAfter: boolean;
  reasonsAfter: string[];
}

export interface SinglePointDto {
  unit: string;
  health: string;
  verdict: string;
  unconfirmed: boolean;
  changes: ServiceChangeDto[];
}

export interface OutOfServiceDto {
  unit: string;
  reason: string;
}
