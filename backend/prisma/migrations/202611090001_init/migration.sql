CREATE TYPE "UserRole" AS ENUM ('COMMUNITY', 'RESPONDER', 'ADMIN');
CREATE TYPE "IncidentStatus" AS ENUM ('NEW', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED');
CREATE TYPE "VerificationStatus" AS ENUM ('UNVERIFIED', 'VERIFIED', 'DISPUTED');
CREATE TYPE "EmergencyCategory" AS ENUM ('MEDICAL', 'FIRE', 'FLOOD', 'ROAD_HAZARD', 'SECURITY', 'EARTHQUAKE', 'OTHER');

CREATE TABLE "User" (
  "id" UUID NOT NULL,
  "email" TEXT NOT NULL,
  "displayName" TEXT NOT NULL,
  "passwordHash" TEXT NOT NULL,
  "role" "UserRole" NOT NULL DEFAULT 'COMMUNITY',
  "active" BOOLEAN NOT NULL DEFAULT true,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "User_email_key" ON "User"("email");

CREATE TABLE "IncidentMedia" (
  "id" UUID NOT NULL,
  "ownerId" UUID NOT NULL,
  "storageName" TEXT NOT NULL,
  "clientUploadId" TEXT,
  "mimeType" TEXT NOT NULL,
  "byteSize" INTEGER NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "IncidentMedia_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "IncidentMedia_storageName_key" ON "IncidentMedia"("storageName");
CREATE UNIQUE INDEX "IncidentMedia_clientUploadId_key" ON "IncidentMedia"("clientUploadId");

CREATE TABLE "Incident" (
  "id" UUID NOT NULL,
  "clientEventId" TEXT NOT NULL,
  "messageId" TEXT NOT NULL,
  "reporterId" UUID NOT NULL,
  "category" "EmergencyCategory" NOT NULL,
  "severity" INTEGER NOT NULL DEFAULT 2,
  "description" VARCHAR(3000) NOT NULL,
  "latitude" DOUBLE PRECISION,
  "longitude" DOUBLE PRECISION,
  "locationConsent" BOOLEAN NOT NULL DEFAULT false,
  "affectedCount" INTEGER,
  "assistance" VARCHAR(1000),
  "mediaId" UUID,
  "status" "IncidentStatus" NOT NULL DEFAULT 'NEW',
  "verification" "VerificationStatus" NOT NULL DEFAULT 'UNVERIFIED',
  "version" INTEGER NOT NULL DEFAULT 1,
  "reportedAt" TIMESTAMP(3) NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "Incident_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "Incident_mediaId_fkey" FOREIGN KEY ("mediaId") REFERENCES "IncidentMedia"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT "Incident_reporterId_fkey" FOREIGN KEY ("reporterId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE UNIQUE INDEX "Incident_clientEventId_key" ON "Incident"("clientEventId");
CREATE UNIQUE INDEX "Incident_mediaId_key" ON "Incident"("mediaId");
CREATE INDEX "Incident_status_reportedAt_idx" ON "Incident"("status", "reportedAt");
CREATE INDEX "Incident_latitude_longitude_idx" ON "Incident"("latitude", "longitude");
CREATE INDEX "Incident_reporterId_reportedAt_idx" ON "Incident"("reporterId", "reportedAt");

CREATE TABLE "SyncEvent" (
  "eventId" TEXT NOT NULL,
  "messageId" TEXT NOT NULL,
  "eventType" TEXT NOT NULL,
  "userId" UUID NOT NULL,
  "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "SyncEvent_pkey" PRIMARY KEY ("eventId"),
  CONSTRAINT "SyncEvent_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE INDEX "SyncEvent_userId_receivedAt_idx" ON "SyncEvent"("userId", "receivedAt");

CREATE TABLE "IncidentDispute" (
  "id" UUID NOT NULL,
  "incidentId" UUID NOT NULL,
  "userId" UUID NOT NULL,
  "reason" VARCHAR(500) NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "IncidentDispute_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "IncidentDispute_incidentId_fkey" FOREIGN KEY ("incidentId") REFERENCES "Incident"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT "IncidentDispute_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE UNIQUE INDEX "IncidentDispute_incidentId_userId_key" ON "IncidentDispute"("incidentId", "userId");
CREATE INDEX "IncidentMedia_ownerId_idx" ON "IncidentMedia"("ownerId");
ALTER TABLE "IncidentMedia" ADD CONSTRAINT "IncidentMedia_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
