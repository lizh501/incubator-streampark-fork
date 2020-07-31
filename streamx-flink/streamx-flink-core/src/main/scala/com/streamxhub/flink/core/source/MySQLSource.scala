/**
 * Copyright (c) 2019 The StreamX Project
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.streamxhub.flink.core.source

import java.util.Properties

import com.streamxhub.common.util.{JdbcUtils, Logger}
import com.streamxhub.flink.core.StreamingContext
import com.streamxhub.flink.core.enums.ApiType
import com.streamxhub.flink.core.enums.ApiType.ApiType
import com.streamxhub.flink.core.function.{GetSQLFunction, ResultSetFunction}
import com.streamxhub.flink.core.wrapper.JdbcQuery
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.runtime.state.{CheckpointListener, FunctionInitializationContext, FunctionSnapshotContext}
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
import org.apache.flink.streaming.api.functions.source.{RichSourceFunction, SourceFunction}
import org.apache.flink.streaming.api.scala.DataStream

import scala.annotation.meta.param
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.Map
import scala.util.{Failure, Success, Try}

/**
 * 提供端到端的精准一次.可回放,需要用户参与才能完成...
 */
object MySQLSource {

  def apply(@(transient@param) ctx: StreamingContext, overrideParams: Map[String, String] = Map.empty[String, String]): MySQLSource = new MySQLSource(ctx, overrideParams)

}

class MySQLSource(@(transient@param) val ctx: StreamingContext, overrideParams: Map[String, String] = Map.empty[String, String]) {

  /**
   *
   * @param sqlFun
   * @param fun
   * @param jdbc
   * @tparam R
   * @return
   */
  def getDataStream[R: TypeInformation](sqlFun: JdbcQuery => JdbcQuery, fun: Iterable[Map[String, _]] => Iterable[R])(implicit jdbc: Properties): DataStream[R] = {
    overrideParams.foreach(x => jdbc.put(x._1, x._2))
    val mysqlFun = new MySQLSourceFunction[R](jdbc, sqlFun, fun)
    ctx.addSource(mysqlFun)
  }

}

/**
 *
 * @tparam R
 */
private[this] class MySQLSourceFunction[R: TypeInformation](apiType: ApiType = ApiType.Scala, jdbc: Properties) extends RichSourceFunction[R] with CheckpointListener with CheckpointedFunction with Logger {

  @volatile private[this] var running = true
  private[this] var scalaSqlFunc: JdbcQuery => JdbcQuery = _
  private[this] var scalaResultFunc: Function[Iterable[Map[String, _]], Iterable[R]] = _
  private[this] var javaSqlFunc: GetSQLFunction = _
  private[this] var javaResultFunc: ResultSetFunction[R] = _

  @transient private var jdbcQuery: JdbcQuery = _

  //value保存的是sql查询条件..
  @transient private var state: ListState[JdbcQuery] = _

  private val OFFSETS_STATE = "mysql-source-query-states"

  //for Scala
  def this(jdbc: Properties, sqlFunc: JdbcQuery => JdbcQuery, resultFunc: Iterable[Map[String, _]] => Iterable[R]) = {
    this(ApiType.Scala, jdbc)
    this.scalaSqlFunc = sqlFunc
    this.scalaResultFunc = resultFunc
  }

  //for JAVA
  def this(jdbc: Properties, javaSqlFunc: GetSQLFunction, javaResultFunc: ResultSetFunction[R]) {
    this(ApiType.JAVA, jdbc)
    this.javaSqlFunc = javaSqlFunc
    this.javaResultFunc = javaResultFunc
  }

  @throws[Exception]
  override def run(@(transient@param) ctx: SourceFunction.SourceContext[R]): Unit = {
    while (this.running) {
      ctx.getCheckpointLock.synchronized {
        val backQuery = if (jdbcQuery == null) null else {
          jdbcQuery.setOffset(jdbcQuery.getLastOffset)
          jdbcQuery
        }
        jdbcQuery = apiType match {
          case ApiType.Scala => scalaSqlFunc(backQuery)
          case ApiType.JAVA => javaSqlFunc.getSQL(backQuery)
        }
        val result: List[Map[String, _]] = apiType match {
          case ApiType.Scala => JdbcUtils.select(jdbcQuery.getSQL, _.setFetchSize(jdbcQuery.getFetchSize))(jdbc)
          case ApiType.JAVA => JdbcUtils.select(jdbcQuery.getSQL, _.setFetchSize(jdbcQuery.getFetchSize))(jdbc)
        }
        apiType match {
          case ApiType.Scala => scalaResultFunc(result).foreach(ctx.collect)
          case ApiType.JAVA => javaResultFunc.result(result.map(_.asJava)).foreach(ctx.collect)
        }
        //记录最后的offset
        Try {
          result.maxBy(_ (jdbcQuery.getField).toString).get(jdbcQuery.getField).get
        } match {
          case Success(v) => jdbcQuery.setLastOffset(v.toString)
          case Failure(_) =>
        }
        jdbcQuery.setSize(result.size)
      }
    }
  }

  override def cancel(): Unit = this.running = false

  override def notifyCheckpointComplete(checkpointId: Long): Unit = {
    logger.info(s"[StreamX] MySQLSource checkpointComplete: $checkpointId")
  }

  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    if (running) {
      state.clear()
      state.add(jdbcQuery)
    } else {
      logger.error("[StreamX] MySQLSource snapshotState called on closed source")
    }
  }

  /**
   * 从checkpoint中恢复数据....
   *
   * @param context
   */
  override def initializeState(context: FunctionInitializationContext): Unit = {
    state = context.getOperatorStateStore.getUnionListState(new ListStateDescriptor(OFFSETS_STATE, classOf[JdbcQuery]))
    Try(state.get.head) match {
      case Success(q) => jdbcQuery = q
      case _ =>
    }
  }
}

