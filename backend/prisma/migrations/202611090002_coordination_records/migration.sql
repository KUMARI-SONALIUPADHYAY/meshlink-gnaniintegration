CREATE TABLE "CoordinationRecord" (
  "eventId" TEXT NOT NULL,
  "messageId" TEXT NOT NULL,
  "recordType" TEXT NOT NULL,
  "payload" JSONB NOT NULL,
  "userId" UUID NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "CoordinationRecord_pkey" PRIMARY KEY ("eventId"),
  CONSTRAINT "CoordinationRecord_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE INDEX "CoordinationRecord_recordType_createdAt_idx" ON "CoordinationRecord"("recordType", "createdAt");
CREATE INDEX "CoordinationRecord_userId_createdAt_idx" ON "CoordinationRecord"("userId", "createdAt");
