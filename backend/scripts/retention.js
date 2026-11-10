import 'dotenv/config';
import { unlink } from 'node:fs/promises';
import path from 'node:path';
import { PrismaClient, IncidentStatus } from '@prisma/client';
const prisma = new PrismaClient();
const uploadDir = path.resolve(process.env.UPLOAD_DIR || './uploads');
try {
  const cutoff = new Date(Date.now() - 365 * 24 * 60 * 60 * 1000);
  const old = await prisma.incident.findMany({ where: { status: IncidentStatus.RESOLVED, updatedAt: { lt: cutoff } }, select: { id: true, media: { select: { id: true, storageName: true } } } });
  const media = old.flatMap(x => x.media ? [x.media] : []);
  if (old.length) {
    await prisma.incident.deleteMany({ where: { id: { in: old.map(x => x.id) } } });
    if (media.length) await prisma.incidentMedia.deleteMany({ where: { id: { in: media.map(x => x.id) } } });
  }
  const orphanCutoff = new Date(Date.now() - 24 * 60 * 60 * 1000);
  const orphanMedia = await prisma.incidentMedia.findMany({ where: { incident: { is: null }, createdAt: { lt: orphanCutoff } }, select: { id: true, storageName: true } });
  if (orphanMedia.length) await prisma.incidentMedia.deleteMany({ where: { id: { in: orphanMedia.map(x => x.id) } } });
  const oldCoordination = await prisma.coordinationRecord.deleteMany({ where: { createdAt: { lt: cutoff } } });
  const oldReceipts = await prisma.syncEvent.deleteMany({ where: { receivedAt: { lt: new Date(Date.now() - 400 * 24 * 60 * 60 * 1000) } } });
  await Promise.all([...media, ...orphanMedia].map(x => unlink(path.join(uploadDir, x.storageName)).catch(() => {})));
  process.stdout.write(`Retained active/recent incidents; purged ${old.length} resolved incidents, ${orphanMedia.length} orphan media files and ${oldReceipts.count} old sync receipts and ${oldCoordination.count} old coordination records.\n`);
} finally { await prisma.$disconnect(); }
