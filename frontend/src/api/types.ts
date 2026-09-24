export interface CreateSessionRequest {
  idea: string
}

export interface CreateSessionResponse {
  id: string
  cleanIdea?: string
  moderated?: boolean
}

export type FieldId =
  | 'target_user'
  | 'problem_alternative'
  | 'desired_outcome'
  | 'category_competitors'
  | 'founder_goal'
  | 'constraints'
  | 'tone_hints'
  | 'proof_advantage'

export interface BriefFieldValue {
  value: string
  confidence: number
  evidence?: string
  assumption?: boolean
}

export interface BriefState {
  fields: Partial<Record<FieldId, BriefFieldValue>> & Record<string, BriefFieldValue | undefined>
  /** Snake keys come from BriefState.toMap (GET session / PATCH brief). */
  question_count?: number
  current_field?: string | null
  questionCount?: number
  overallConfidence?: number
  nextQuestion?: string | null
}

export interface Trait {
  name: string
  whyFits: string
  behavior: string
  neverBecome: string
}

export interface VoiceSpec {
  formality: number
  sentenceWords: [number, number]
  humorLevel: string
  bannedWords: string[]
  signatureMoves: string[]
}

export interface PersonalityStageData {
  traits: Trait[]
  voice: VoiceSpec
  avoidList: string[]
}

export interface RegenerateStageRequest {
  note?: string
}

export interface NameAttempt {
  name: string
  antiGenericScore: number
  criticScore: number
}

export interface BrandName {
  name: string
  territory: string
  rationale: string
  length: number
  pronounceability: number
  lexiconScore?: number
  embeddingScore?: number
  criticScore?: number
  antiGenericScore: number
  domainSignal?: string
  attempts?: NameAttempt[]
}

export interface NamingTerritory {
  name: string
  rationale: string
  whyFitsPersonality: string
}

export interface NamingStageData {
  territories: NamingTerritory[]
  names: BrandName[]
  domainDisclaimer?: string
}

export interface SelectNameRequest {
  name?: string
  nameIndex?: number
}

export interface SelectNameResponse {
  name: string
  nameIndex: number
  territory: string
}

export interface TaglineAttempt {
  text: string
  score: number
  delta: number
}

export interface TaglineOption {
  text: string
  score: number
  attempts: TaglineAttempt[]
}

export interface MessageHierarchy {
  primary: string
  secondary: string[]
  proof: string[]
}

export interface MessagesStageData {
  taglines: TaglineOption[]
  pitch: string
  hierarchy: MessageHierarchy
  selected?: number | null
}

export interface SelectTaglineRequest {
  index: number
}

export interface SelectTaglineResponse {
  selected: number
  tagline: string
  pitch: string
  messages: MessagesStageData
}

export interface Palette {
  bg: string
  surface: string
  accent: string
  fg: string
  muted: string
}

export interface LogoGrammar {
  form: 'wordmark' | 'monogram' | 'wordmark+mark'
  letters: string
  shapeNotes?: string
}

export interface VisualDirection {
  moodWords: string[]
  seedHue: number
  saturation: 'low' | 'medium' | 'high'
  shapeLanguage: string
  fontPairIds: string[]
  logoGrammar: LogoGrammar
  imagery: string
  avoidList: string[]
}

export interface VisualBoard {
  palette: Palette
  fonts: string[]
  shape: string
  logoSvg: string
  direction: VisualDirection
}

export interface PatchVisualTokensRequest {
  seedHue?: number
  accent?: string
  saturation?: 'low' | 'medium' | 'high'
}

export interface LaunchHero {
  headline: string
  subhead: string
  cta: string
}

export interface LaunchAssets {
  hero: LaunchHero
  pitch: string
  posts: string[]
  bioShort: string
  bioLong: string
}

export interface CreatedShare {
  token: string
  expiresAt: string | number
}

export type ExportFormat = 'md' | 'json'

export interface BrandDna {
  personality?: {
    traits?: Trait[]
    avoidList?: string[]
  }
  voice?: VoiceSpec
  naming?: NamingStageData
  identity?: { name?: string; tagline?: string; pitch?: string }
  messages?: MessagesStageData
  visual?: VisualBoard
  position?: Partial<Position>
  assets?: LaunchAssets
}

export interface SessionState {
  id: string
  status: string
  briefState?: BriefState
  brandDna?: BrandDna | unknown
}

export interface InterviewAnswerRequest {
  answer?: string
  skip?: boolean
}

export interface InterviewAnswerResponse {
  briefState: BriefState
  nextQuestion: string | null
  fieldId?: string
  overallConfidence: number
  questionCount: number
  done: boolean
}

export interface PatchBriefRequest {
  fields: Record<string, { value: string }>
}

/** PATCH /brief returns BriefState map directly (not wrapped). */
export type PatchBriefResponse = BriefState

export interface ApiErrorBody {
  error?: string
  message?: string
}

export interface Position {
  mandate: 'native' | 'contrarian' | 'emotional'
  category: string
  frameOfReference: string
  target: string
  insight: string
  differentiator: string
  valueProposition: string
  proofPoints: string[]
  competitiveAngle: string
  biggestRisk: string
}

export interface PositionScore {
  mandate: 'native' | 'contrarian' | 'emotional'
  audienceFit: number
  distinctiveness: number
  credibility: number
  memorability: number
  feasibility: number
  explanation: string
  total: number
}

export interface JudgeResult {
  scores: PositionScore[]
  /** Backend omits this field; only show the banner when present and failed. */
  differenceCheck?: { ok: boolean; note?: string }
}

export interface SelectPositionRequest {
  index: number
  edits?: Partial<Position>
}

export interface SelectPositionResponse {
  selected: Position
}

export interface AuditFinding {
  dimension?: string | null
  asset?: string | null
  quote?: string | null
  rule?: string | null
  severity?: string | null
}

export interface AuditDimension {
  dimension: string
  weight: number
  score: number
  evidence: string[]
  deterministicFindings: AuditFinding[]
}

export interface AuditResult {
  dimensions: AuditDimension[]
  overall: number
  conflicts: AuditFinding[]
  reviseInstructions?: Record<string, string[]>
}

export interface AuditDiff {
  asset?: string | null
  dimension?: string | null
  before: string
  after: string
}

/** POST /api/sessions/{id}/audit — nested (SSE/JSON) or flat. */
export interface AuditResponse {
  result?: AuditResult
  diffs?: AuditDiff[]
  reviseRounds?: number
  degraded?: boolean
  dimensions?: AuditDimension[]
  overall?: number
  conflicts?: AuditFinding[]
  revised?: boolean
}

export type DriftAssetType = 'post' | 'email' | 'landing'

export interface DriftCheckRequest {
  text: string
  assetType: DriftAssetType
}

export interface DriftVerdict {
  dimension: string
  weight: number
  score: number
  pass: boolean
  evidence: string[]
  deterministicFindings: AuditFinding[]
}

export interface DriftCheckResponse {
  assetType: string
  overall: number
  pass: boolean
  dimensions: DriftVerdict[]
  flaggedPhrases: string[]
  rewrite: string
}

/** GET /api/sessions/{id}/stage-runs — mirrors StageRunRepository.StageRunView. */
export interface StageRun {
  id: string
  stage: string
  model: string | null
  latencyMs: number | null
  promptVersion: string | null
  status: string
  tokensIn: number | null
  tokensOut: number | null
  stale: boolean
  locked: boolean
  score: number | null
}
