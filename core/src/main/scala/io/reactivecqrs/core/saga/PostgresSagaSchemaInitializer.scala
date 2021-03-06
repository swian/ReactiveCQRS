package io.reactivecqrs.core.saga

import scalikejdbc._

class PostgresSagaSchemaInitializer {
  def initSchema(): Unit = {
    createSagasTable()
  }

  private def createSagasTable(): Unit = DB.autoCommit { implicit session =>
    sql"""
        CREATE TABLE IF NOT EXISTS sagas (
          name VARCHAR NOT NULL,
          saga_id BIGINT NOT NULL,
          user_id BIGINT NOT NULL,
          respond_to VARCHAR(256) NOT NULL,
          creation_time TIMESTAMP NOT NULL,
          phase VARCHAR(16) NOT NULL,
          step INT NOT NULL,
          update_time TIMESTAMP NOT NULL,
          saga_order TEXT NOT NULL,
          order_type_id INT NOT NULL)
      """.execute().apply()
  }
}
