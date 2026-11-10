import 'dotenv/config';
import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import rateLimit from 'express-rate-limit';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import multer from 'multer';
import { randomUUID } from 'node:crypto';
import { mkdir, unlink } from 'node:fs/promises';
import path from 'node:path';
import { fileTypeFromFile } from 'file-type';
import { PrismaClient, UserRole, IncidentStatus, VerificationStatus, EmergencyCategory } from '@prisma/client';
import { z } from 'zod';

const prisma = new PrismaClient();
const app = express();
const port = Number(process.env.PORT || 8080);
const uploadDir = path.resolve(process.env.UPLOAD_DIR || './uploads');
const maxUpload = Math.min(Number(process.env.MAX_UPLOAD_BYTES || 5 * 1024 * 1024), 5 * 1024 * 1024);
const jwtSecret = process.env.JWT_SECRET || '';
const issuer = process.env.JWT_ISSUER || 'meshlink-cloud';
const audience = process.env.JWT_AUDIENCE || 'meshlink-android';
if (jwtSecret.length < 32) throw new Error('JWT_SECRET must contain at least 32 characters.');
await mkdir(uploadDir, { recursive: true });
app.disable('x-powered-by');
if (process.env.TRUST_PROXY === '1') app.set('trust proxy', 1);
app.use(helmet({ crossOriginResourcePolicy: { policy: 'same-site' } }));
const origins = (process.env.CORS_ORIGINS || '').split(',').map(x => x.trim()).filter(Boolean);
app.use(cors({ origin: origins.length ? origins : false, methods: ['GET','POST','PATCH'], allowedHeaders: ['Content-Type','Authorization'] }));
app.use(express.json({ limit: '256kb' }));
app.use('/api/auth', rateLimit({ windowMs: 15 * 60 * 1000, limit: 20, standardHeaders: 'draft-8', legacyHeaders: false }));
app.use('/api', rateLimit({ windowMs: 60 * 1000, limit: 120, standardHeaders: 'draft-8', legacyHeaders: false }));

const registerSchema = z.object({ email: z.string().email().max(254), displayName: z.string().trim().min(2).max(60), password: z.string().min(12).max(128) });
const loginSchema = z.object({ email: z.string().email(), password: z.string().min(1).max(128) });
const incidentSchema = z.object({ clientEventId: z.string().min(8).max(128), messageId: z.string().min(1).max(128), category: z.nativeEnum(EmergencyCategory), severity: z.number().int().min(1).max(5).default(2), description: z.string().trim().min(5).max(3000), latitude: z.number().min(-90).max(90).nullable().optional(), longitude: z.number().min(-180).max(180).nullable().optional(), locationConsent: z.boolean().default(false), affectedCount: z.number().int().min(0).max(100000).nullable().optional(), assistance: z.string().max(1000).nullable().optional(), mediaId: z.string().uuid().nullable().optional(), reportedAt: z.string().datetime().optional() }).refine(v => v.locationConsent || (v.latitude == null && v.longitude == null), { message: 'Coordinates require explicit location consent.' }).refine(v => (v.latitude == null) === (v.longitude == null), { message: 'Latitude and longitude must be provided together.' });
const statusSchema = z.object({ status: z.nativeEnum(IncidentStatus), expectedVersion: z.number().int().positive() });
const verifySchema = z.object({ verification: z.enum(['VERIFIED','DISPUTED']), expectedVersion: z.number().int().positive() });
const syncPayloadSchema = z.object({ schema_version: z.number().int().positive().optional(), message_id: z.string().max(128).optional(), summary: z.string().max(2000).optional(), description: z.string().max(3000).optional(), category: z.nativeEnum(EmergencyCategory).optional(), severity: z.number().int().min(1).max(5).optional(), locationConsent: z.boolean().optional(), latitude: z.number().min(-90).max(90).optional(), longitude: z.number().min(-180).max(180).optional(), affectedCount: z.number().int().min(0).max(100000).optional(), assistance: z.string().max(1000).optional(), mediaId: z.string().uuid().optional(), record_type: z.enum(['INCIDENT','PEOPLE','RESOURCES','VOLUNTEER','SAFE_ZONE']).optional(), people_affected: z.number().int().min(0).max(100000).nullable().optional(), children_count: z.number().int().min(0).max(100000).nullable().optional(), elderly_count: z.number().int().min(0).max(100000).nullable().optional(), injured_count: z.number().int().min(0).max(100000).nullable().optional(), disability_support_count: z.number().int().min(0).max(100000).nullable().optional(), pregnant_count: z.number().int().min(0).max(100000).nullable().optional(), food_kits: z.number().int().min(0).max(1000000).nullable().optional(), water_litres: z.number().int().min(0).max(10000000).nullable().optional(), first_aid_kits: z.number().int().min(0).max(100000).nullable().optional(), blankets: z.number().int().min(0).max(1000000).nullable().optional(), shelter_spaces: z.number().int().min(0).max(1000000).nullable().optional(), volunteers_available: z.number().int().min(0).max(100000).nullable().optional(), volunteer_skills: z.string().max(500).optional(), safe_zone_name: z.string().max(120).optional(), safe_zone_capacity: z.number().int().min(0).max(100000).nullable().optional(), safe_zone_accessibility: z.string().max(500).optional(), safe_zone_open: z.boolean().optional() }).passthrough();

function signToken(user) { return jwt.sign({ sub: user.id, role: user.role }, jwtSecret, { algorithm: 'HS256', expiresIn: '30m', issuer, audience }); }
async function authenticate(req, res, next) {
  try {
    const raw = req.headers.authorization || '';
    if (!raw.startsWith('Bearer ')) return res.status(401).json({ error: 'AUTH_REQUIRED' });
    const claims = jwt.verify(raw.slice(7), jwtSecret, { algorithms: ['HS256'], issuer, audience });
    const user = await prisma.user.findUnique({ where: { id: claims.sub }, select: { id: true, email: true, displayName: true, role: true, active: true } });
    if (!user?.active) return res.status(401).json({ error: 'ACCOUNT_INACTIVE' });
    req.user = user; next();
  } catch { return res.status(401).json({ error: 'INVALID_OR_EXPIRED_TOKEN' }); }
}
const allowRoles = (...roles) => (req, res, next) => roles.includes(req.user.role) ? next() : res.status(403).json({ error: 'INSUFFICIENT_ROLE' });
const imageStorage = multer.diskStorage({ destination: uploadDir, filename: (_req, _file, cb) => cb(null, `${randomUUID()}.upload`) });
const upload = multer({ storage: imageStorage, limits: { fileSize: maxUpload, files: 1, fields: 1, fieldSize: 256, parts: 2 }, fileFilter: (_req, file, cb) => cb(null, ['image/jpeg','image/png','image/webp'].includes(file.mimetype)) });
const incidentSelect = { id: true, clientEventId: true, messageId: true, category: true, severity: true, description: true, latitude: true, longitude: true, locationConsent: true, affectedCount: true, assistance: true, status: true, verification: true, version: true, reportedAt: true, updatedAt: true, reporter: { select: { id: true, displayName: true, role: true } }, mediaId: true };

app.get('/health', async (_req, res) => {
  try { await prisma.$queryRaw`SELECT 1`; res.json({ status: 'ok', service: 'meshlink-cloud-api', database: 'ready', time: new Date().toISOString() }); }
  catch { res.status(503).json({ status: 'unavailable', service: 'meshlink-cloud-api', database: 'unavailable' }); }
});
app.post('/api/auth/register', async (req, res, next) => {
  try {
    const input = registerSchema.parse(req.body);
    const email = input.email.toLowerCase();
    const passwordHash = await bcrypt.hash(input.password, 12);
    const user = await prisma.user.create({ data: { email, displayName: input.displayName, passwordHash, role: UserRole.COMMUNITY }, select: { id: true, email: true, displayName: true, role: true } });
    res.status(201).json({ token: signToken(user), user });
  } catch (e) { if (e.code === 'P2002') return res.status(409).json({ error: 'EMAIL_ALREADY_REGISTERED' }); next(e); }
});
app.post('/api/auth/login', async (req, res, next) => {
  try {
    const input = loginSchema.parse(req.body);
    const user = await prisma.user.findUnique({ where: { email: input.email.toLowerCase() } });
    if (!user || !user.active || !(await bcrypt.compare(input.password, user.passwordHash))) return res.status(401).json({ error: 'INVALID_CREDENTIALS' });
    res.json({ token: signToken(user), user: { id: user.id, email: user.email, displayName: user.displayName, role: user.role } });
  } catch (e) { next(e); }
});
app.get('/api/me', authenticate, (req, res) => res.json({ user: req.user }));
app.patch('/api/me', authenticate, async (req, res, next) => {
  try { const displayName = z.string().trim().min(2).max(60).parse(req.body.displayName); const user = await prisma.user.update({ where: { id: req.user.id }, data: { displayName }, select: { id: true, email: true, displayName: true, role: true } }); res.json({ user }); } catch (e) { next(e); }
});
app.delete('/api/me', authenticate, async (req, res, next) => {
  try {
    const password = z.string().min(1).max(128).parse(req.body.password);
    const account = await prisma.user.findUnique({ where: { id: req.user.id } });
    if (!account || !(await bcrypt.compare(password, account.passwordHash))) return res.status(401).json({ error: 'INVALID_CREDENTIALS' });
    const media = await prisma.incidentMedia.findMany({ where: { ownerId: account.id }, select: { id: true, storageName: true } });
    await prisma.$transaction(async tx => {
      if (media.length) { const ids = media.map(x => x.id); await tx.incident.updateMany({ where: { mediaId: { in: ids } }, data: { mediaId: null } }); await tx.incidentMedia.deleteMany({ where: { id: { in: ids } } }); }
      await tx.incidentDispute.deleteMany({ where: { userId: account.id } });
      await tx.user.update({ where: { id: account.id }, data: { email: `deleted+${account.id}@invalid.meshlink`, displayName: 'Deleted account', passwordHash: await bcrypt.hash(randomUUID()+randomUUID(),12), active: false } });
    });
    await Promise.all(media.map(x => unlink(path.join(uploadDir,x.storageName)).catch(() => {})));
    res.status(204).end();
  } catch (e) { next(e); }
});

app.post('/api/media', authenticate, (req, res, next) => upload.single('image')(req, res, async err => {
  if (err) return res.status(400).json({ error: 'INVALID_IMAGE', message: err.code === 'LIMIT_FILE_SIZE' ? 'Image exceeds 5 MB.' : 'Only JPEG, PNG and WebP images are accepted.' });
  try {
    if (!req.file) return res.status(400).json({ error: 'IMAGE_REQUIRED' });
    const clientUploadId = typeof req.body.clientUploadId === 'string' ? req.body.clientUploadId.slice(0,128) : null;
    if (clientUploadId) {
      const existing = await prisma.incidentMedia.findUnique({ where: { clientUploadId } });
      if (existing) {
        await unlink(req.file.path).catch(() => {});
        if (existing.ownerId !== req.user.id) return res.status(409).json({ error: 'UPLOAD_ID_ALREADY_USED' });
        return res.status(200).json({ mediaId: existing.id, duplicate: true });
      }
    }
    const detected = await fileTypeFromFile(req.file.path);
    if (!detected || !['image/jpeg','image/png','image/webp'].includes(detected.mime) || detected.mime !== req.file.mimetype) { await unlink(req.file.path).catch(() => {}); return res.status(400).json({ error: 'INVALID_IMAGE_CONTENT' }); }
    res.setHeader('Cache-Control','private, no-store');
    const media = await prisma.incidentMedia.create({ data: { ownerId: req.user.id, storageName: req.file.filename, clientUploadId, mimeType: req.file.mimetype, byteSize: req.file.size } });
    res.status(201).json({ mediaId: media.id });
  } catch (e) { if (req.file?.path) await unlink(req.file.path).catch(() => {}); next(e); }
}));
app.get('/api/media/:id', authenticate, async (req, res, next) => {
  try {
    const media = await prisma.incidentMedia.findUnique({ where: { id: req.params.id }, include: { incident: { select: { reporterId: true } } } });
    if (!media) return res.status(404).json({ error: 'MEDIA_NOT_FOUND' });
    if (media.ownerId !== req.user.id && ![UserRole.RESPONDER,UserRole.ADMIN].includes(req.user.role)) return res.status(403).json({ error: 'FORBIDDEN' });
    res.setHeader('Cache-Control','private, no-store');
    res.type(media.mimeType).sendFile(path.join(uploadDir, media.storageName));
  } catch (e) { next(e); }
});

app.post('/api/incidents', authenticate, async (req, res, next) => {
  try {
    const input = incidentSchema.parse(req.body);
    if (input.mediaId) {
      const media = await prisma.incidentMedia.findUnique({ where: { id: input.mediaId } });
      if (!media || media.ownerId !== req.user.id) return res.status(403).json({ error: 'MEDIA_NOT_OWNED' });
    }
    const incident = await prisma.incident.upsert({ where: { clientEventId: input.clientEventId }, create: { ...input, reporterId: req.user.id, reportedAt: input.reportedAt ? new Date(input.reportedAt) : new Date() }, update: {}, select: incidentSelect });
    if (incident.reporter.id !== req.user.id && ![UserRole.RESPONDER,UserRole.ADMIN].includes(req.user.role)) return res.status(409).json({ error: 'EVENT_ID_ALREADY_USED' });
    res.status(incident.version === 1 ? 201 : 200).json({ incident });
  } catch (e) { next(e); }
});
app.get('/api/incidents', authenticate, async (req, res, next) => {
  try {
    const limit = z.coerce.number().int().min(1).max(100).default(50).parse(req.query.limit ?? 50);
    const status = req.query.status ? z.nativeEnum(IncidentStatus).parse(req.query.status) : undefined;
    const category = req.query.category ? z.nativeEnum(EmergencyCategory).parse(req.query.category) : undefined;
    const hasLat = req.query.lat != null, hasLng = req.query.lng != null;
    if (hasLat !== hasLng) return res.status(400).json({ error: 'BOTH_COORDINATES_REQUIRED' });
    const lat = hasLat ? z.coerce.number().min(-90).max(90).parse(req.query.lat) : null;
    const lng = hasLng ? z.coerce.number().min(-180).max(180).parse(req.query.lng) : null;
    const radius = z.coerce.number().min(1).max(200).default(25).parse(req.query.radiusKm ?? 25);
    const baseWhere = { status: status ? status : { not: IncidentStatus.RESOLVED }, ...(category ? { category } : {}) };
    const latitudeDelta = lat == null ? 0 : radius / 110.574;
    const longitudeDelta = lat == null ? 0 : radius / (111.320 * Math.max(Math.cos(lat * Math.PI / 180), 0.01));
    const where = lat == null || lng == null ? baseWhere : { ...baseWhere, latitude: { gte: Math.max(-90,lat-latitudeDelta), lte: Math.min(90,lat+latitudeDelta) }, longitude: { gte: Math.max(-180,lng-longitudeDelta), lte: Math.min(180,lng+longitudeDelta) } };
    const candidates = await prisma.incident.findMany({ where, orderBy: { reportedAt: 'desc' }, take: lat == null ? limit : 1000, select: incidentSelect });
    const filtered = lat == null || lng == null ? candidates : candidates.filter(i => i.latitude != null && i.longitude != null && haversine(lat,lng,i.latitude,i.longitude) <= radius).slice(0,limit);
    res.json({ incidents: filtered, count: filtered.length });
  } catch (e) { next(e); }
});
app.get('/api/incidents/:id', authenticate, async (req, res, next) => {
  try { const incident = await prisma.incident.findUnique({ where: { id: req.params.id }, select: incidentSelect }); if (!incident) return res.status(404).json({ error: 'INCIDENT_NOT_FOUND' }); res.json({ incident }); } catch (e) { next(e); }
});
app.patch('/api/incidents/:id/status', authenticate, allowRoles(UserRole.RESPONDER,UserRole.ADMIN), async (req, res, next) => {
  try {
    const input = statusSchema.parse(req.body);
    const current = await prisma.incident.findUnique({ where: { id: req.params.id }, select: { status: true, version: true } });
    if (!current) return res.status(404).json({ error: 'INCIDENT_NOT_FOUND' });
    const transitions = { NEW: ['ACKNOWLEDGED','IN_PROGRESS','RESOLVED'], ACKNOWLEDGED: ['IN_PROGRESS','RESOLVED'], IN_PROGRESS: ['ACKNOWLEDGED','RESOLVED'], RESOLVED: [] };
    if (!transitions[current.status]?.includes(input.status)) return res.status(409).json({ error: 'INVALID_STATUS_TRANSITION' });
    const result = await prisma.incident.updateMany({ where: { id: req.params.id, version: input.expectedVersion }, data: { status: input.status, version: { increment: 1 } } });
    if (!result.count) return res.status(409).json({ error: 'VERSION_CONFLICT', message: 'Incident changed. Refresh before retrying.' });
    res.json({ incident: await prisma.incident.findUnique({ where: { id: req.params.id }, select: incidentSelect }) });
  } catch (e) { next(e); }
});
app.patch('/api/incidents/:id/verification', authenticate, allowRoles(UserRole.RESPONDER,UserRole.ADMIN), async (req, res, next) => {
  try {
    const input = verifySchema.parse(req.body);
    const result = await prisma.incident.updateMany({ where: { id: req.params.id, version: input.expectedVersion }, data: { verification: input.verification, version: { increment: 1 } } });
    if (!result.count) return res.status(409).json({ error: 'VERSION_CONFLICT' });
    res.json({ incident: await prisma.incident.findUnique({ where: { id: req.params.id }, select: incidentSelect }) });
  } catch (e) { next(e); }
});
app.post('/api/incidents/:id/dispute', authenticate, async (req, res, next) => {
  try {
    const reason = z.string().trim().min(5).max(500).parse(req.body.reason);
    await prisma.incidentDispute.create({ data: { incidentId: req.params.id, userId: req.user.id, reason } });
    await prisma.incident.update({ where: { id: req.params.id }, data: { verification: VerificationStatus.DISPUTED, version: { increment: 1 } } });
    res.status(201).json({ status: 'DISPUTED' });
  } catch (e) { if (e.code === 'P2002') return res.status(409).json({ error: 'ALREADY_DISPUTED' }); next(e); }
});

app.get('/api/coordination/summary', authenticate, allowRoles(UserRole.RESPONDER, UserRole.ADMIN), async (_req, res, next) => {
  try {
    const records = await prisma.coordinationRecord.findMany({ orderBy: { createdAt: 'desc' }, take: 500, select: { recordType: true, payload: true, createdAt: true } });
    const sum = (key) => records.filter(r => ['PEOPLE','INCIDENT'].includes(r.recordType)).reduce((n,r) => n + (Number.isInteger(r.payload?.[key]) ? Math.max(0,r.payload[key]) : 0), 0);
    const resources = ['food_kits','water_litres','first_aid_kits','blankets','shelter_spaces'];
    const resourceDemand = Object.fromEntries(resources.map(key => [key, records.filter(r => ['RESOURCES','INCIDENT'].includes(r.recordType)).reduce((n,r) => n + (Number.isInteger(r.payload?.[key]) ? Math.max(0,r.payload[key]) : 0), 0)]));
    const volunteersAvailable = records.filter(r => r.recordType === 'VOLUNTEER').reduce((n,r) => n + (Number.isInteger(r.payload?.volunteers_available) ? Math.max(0,r.payload.volunteers_available) : 0), 0);
    res.json({
      records: records.length,
      incidentReports: records.filter(r => r.recordType === 'INCIDENT').length,
      peopleReported: sum('people_affected'), children: sum('children_count'), elderly: sum('elderly_count'),
      injured: sum('injured_count'), disabilitySupport: sum('disability_support_count'), pregnant: sum('pregnant_count'),
      volunteersAvailable, resourceDemand,
      note: 'Latest 500 reports only. Community submissions are unverified and may overlap; totals are report sums, not deduplicated population estimates or official population counts.'
    });
  } catch (e) { next(e); }
});

app.get('/api/coordination/records', authenticate, async (req, res, next) => {
  try {
    const limit = z.coerce.number().int().min(1).max(200).default(100).parse(req.query.limit ?? 100);
    const records = await prisma.coordinationRecord.findMany({
      where: [UserRole.RESPONDER, UserRole.ADMIN].includes(req.user.role) ? {} : { userId: req.user.id },
      orderBy: { createdAt: 'desc' }, take: limit,
      select: { eventId: true, messageId: true, recordType: true, payload: true, createdAt: true, user: { select: { id: true, displayName: true, role: true } } }
    });
    res.json({ records, count: records.length });
  } catch (e) { next(e); }
});

app.post('/api/sync/events', authenticate, async (req, res, next) => {
  try {
    const input = z.object({ events: z.array(z.object({ eventId: z.string().uuid(), messageId: z.string().min(1).max(128), eventType: z.enum(['SOS','EMERGENCY_REPORT','COORDINATION_UPDATE']), payload: syncPayloadSchema, createdAt: z.number().int().positive().max(Date.now() + 5 * 60 * 1000) })).max(50) }).parse(req.body);
    const results = [];
    for (const event of input.events) {
      const already = await prisma.syncEvent.findUnique({ where: { eventId: event.eventId } });
      if (already) { if (already.userId !== req.user.id) return res.status(409).json({ error: 'EVENT_ID_ALREADY_USED' }); results.push({ eventId: event.eventId, accepted: true, duplicate: true }); continue; }
      const created = await prisma.$transaction(async tx => {
        const receipt = await tx.syncEvent.create({ data: { eventId: event.eventId, messageId: event.messageId, eventType: event.eventType, userId: req.user.id, receivedAt: new Date(event.createdAt) } });
        let incident = null;
        if (event.eventType === 'COORDINATION_UPDATE') {
          const p = event.payload;
          const recordType = z.enum(['INCIDENT','PEOPLE','RESOURCES','VOLUNTEER','SAFE_ZONE']).parse(p.record_type);
          await tx.coordinationRecord.upsert({
            where: { eventId: event.eventId },
            create: { eventId: event.eventId, messageId: event.messageId, recordType, payload: p, userId: req.user.id, createdAt: new Date(event.createdAt) },
            update: {}
          });
        } else {
          const p = event.payload;
          const category = Object.values(EmergencyCategory).includes(p.category) ? p.category : EmergencyCategory.OTHER;
          const severity = Number.isInteger(p.severity) ? Math.min(5,Math.max(1,p.severity)) : 2;
          const locationConsent = p.locationConsent === true;
          const latitude = locationConsent && Number.isFinite(p.latitude) && p.latitude >= -90 && p.latitude <= 90 ? p.latitude : null;
          const longitude = locationConsent && Number.isFinite(p.longitude) && p.longitude >= -180 && p.longitude <= 180 ? p.longitude : null;
          if ((latitude == null) !== (longitude == null)) throw Object.assign(new Error('INVALID_COORDINATES'), { statusCode: 400 });
          const mediaId = typeof p.mediaId === 'string' ? p.mediaId : null;
          if (mediaId) {
            const media = await tx.incidentMedia.findUnique({ where: { id: mediaId } });
            if (!media || media.ownerId !== req.user.id) throw Object.assign(new Error('MEDIA_NOT_OWNED'), { statusCode: 403 });
          }
          incident = await tx.incident.upsert({ where: { clientEventId: event.eventId }, create: { clientEventId: event.eventId, messageId: event.messageId, reporterId: req.user.id, category, severity, description: String(p.summary || p.description || 'Emergency alert').slice(0,3000), latitude, longitude, locationConsent, affectedCount: Number.isInteger(p.affectedCount) ? Math.max(0,p.affectedCount) : null, assistance: p.assistance ? String(p.assistance).slice(0,1000) : null, mediaId, reportedAt: new Date(event.createdAt) }, update: {}, select: { id: true } });
        }
        return { receipt, incident };
      });
      results.push({ eventId: event.eventId, accepted: true, incidentId: created.incident?.id || null });
    }
    res.json({ results });
  } catch (e) { next(e); }
});
function haversine(a,b,c,d) { const r=6371, rad=x=>x*Math.PI/180, x=rad(c-a), y=rad(d-b); const h=Math.sin(x/2)**2+Math.cos(rad(a))*Math.cos(rad(c))*Math.sin(y/2)**2; return 2*r*Math.asin(Math.sqrt(h)); }
app.use((err, _req, res, _next) => {
  if (err.statusCode) return res.status(err.statusCode).json({ error: err.message });
  if (err instanceof z.ZodError) return res.status(400).json({ error: 'VALIDATION_ERROR', details: err.issues.map(x => ({ path: x.path.join('.'), message: x.message })) });
  if (err.code === 'P2003' || err.code === 'P2025') return res.status(404).json({ error: 'RESOURCE_NOT_FOUND' });
  if (err.code === 'P2002') return res.status(409).json({ error: 'DUPLICATE_RESOURCE' });
  if (process.env.NODE_ENV !== 'production') console.error('API error:', err.message);
  return res.status(500).json({ error: 'INTERNAL_SERVER_ERROR' });
});
const server = app.listen(port, '0.0.0.0', () => process.stdout.write(`MeshLink API listening on ${port}\n`));
async function shutdown() { server.close(); await prisma.$disconnect(); process.exit(0); }
process.on('SIGINT', shutdown); process.on('SIGTERM', shutdown);
