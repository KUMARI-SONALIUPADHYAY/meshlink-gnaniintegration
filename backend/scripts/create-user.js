import 'dotenv/config';
import bcrypt from 'bcryptjs';
import { PrismaClient, UserRole } from '@prisma/client';
const prisma = new PrismaClient();
try {
  const [email, displayName, role] = process.argv.slice(2);
  if (!email || !displayName || !['RESPONDER','ADMIN'].includes(role)) {
    throw new Error('Usage: read -s PASSWORD; printf %s "$PASSWORD" | npm run admin:create -- <email> <displayName> RESPONDER|ADMIN');
  }
  let password = '';
  for await (const chunk of process.stdin) password += chunk.toString('utf8');
  password = password.trimEnd();
  if (password.length < 16) throw new Error('Privileged account passwords must be at least 16 characters.');
  const user = await prisma.user.upsert({ where: { email: email.toLowerCase() }, create: { email: email.toLowerCase(), displayName, passwordHash: await bcrypt.hash(password,12), role: UserRole[role] }, update: { displayName, passwordHash: await bcrypt.hash(password,12), role: UserRole[role] } });
  process.stdout.write(`Provisioned ${user.role} account ${user.email}\n`);
} finally { await prisma.$disconnect(); }
