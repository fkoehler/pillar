package de.kaufhof.pillar

import java.util.Date

import com.datastax.driver.core.Session
import com.datastax.driver.core.exceptions.AlreadyExistsException

class CassandraMigrator(registry: Registry) extends Migrator {
  override def migrate(session: Session, dateRestriction: Option[Date] = None) {
    val appliedMigrations = AppliedMigrations(session, registry)
    selectMigrationsToReverse(dateRestriction, appliedMigrations).foreach(_.executeDownStatement(session))
    selectMigrationsToApply(dateRestriction, appliedMigrations).foreach(_.executeUpStatement(session))
  }

  override def initialize(session: Session, keyspace: String, replicationStrategy: ReplicationStrategy) {
    createKeyspace(session, keyspace, replicationStrategy)
    createMigrationsTable(session, keyspace)
  }

  override def createKeyspace(session: Session, keyspace: String, replicationStrategy: ReplicationStrategy = SimpleStrategy()) = {
    session.execute(s"CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = ${replicationStrategy.cql}")
  }

  override def createMigrationsTable(session: Session, keyspace: String) = {
    session.execute(
      """
        | CREATE TABLE IF NOT EXISTS %s.applied_migrations (
        |   authored_at timestamp,
        |   description text,
        |   applied_at timestamp,
        |   PRIMARY KEY (authored_at, description)
        |  )
      """.stripMargin.format(keyspace)
    )
  }

  override def destroy(session: Session, keyspace: String) {
    session.execute("DROP KEYSPACE %s".format(keyspace))
  }

  private def selectMigrationsToApply(dateRestriction: Option[Date], appliedMigrations: AppliedMigrations): Seq[Migration] = {
    (dateRestriction match {
      case None => registry.all
      case Some(cutOff) => registry.authoredBefore(cutOff)
    }).filter(!appliedMigrations.contains(_))
  }

  private def selectMigrationsToReverse(dateRestriction: Option[Date], appliedMigrations: AppliedMigrations): Seq[Migration] = {
    (dateRestriction match {
      case None => List.empty[Migration]
      case Some(cutOff) => appliedMigrations.authoredAfter(cutOff)
    }).sortBy(_.authoredAt).reverse
  }
}