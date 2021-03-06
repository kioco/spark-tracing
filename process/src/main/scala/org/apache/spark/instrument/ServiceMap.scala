/* Copyright 2017 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.instrument

import org.apache.spark.rdd.RDD
import scala.util.matching.Regex

case class ServiceRow(trace: Int, uuid: String, start: Long, name: String, host: String, port: Int)

class Trace(val id: Int, subtree: Map[String, Seq[ServiceRow]]) extends Serializable {
  val processes: Seq[Process] = subtree.map(rows => new Process(this, rows._1, rows._2)).toSeq
}

class Process(val trace: Trace, val uuid: String, subtree: Seq[ServiceRow]) extends Serializable {
  val services: Seq[Service] = subtree.map(row => new Service(this, row.host, row.port, row.name, row.start))
}

class Service(val process: Process, val host: String, val port: Int, val name: String, val start: Long) extends Serializable {
  val id: String =
    if (host != "") s"$host $port $name"
    else name
}

object ServiceMap {
  def splitHost(host: String): (String, Int) = {
    val hostPort = host.split("/")(1).split(":")
    (hostPort(0), hostPort(1).toInt)
  }
}

class ServiceMap(events: Seq[RDD[EventTree]], removeServices: Set[Regex]) extends Serializable {
  val traces: Map[Int, Trace] = {
    def serviceRows(traceid: Int, rdd: RDD[EventTree]) = {
      val rows = rdd.filter(_(3)(0).is("Service")).collect.map { x =>
        val host = ServiceMap.splitHost(x(3)(2).get)
        ServiceRow(traceid, x(1).get, x(2).get.toLong, x(3)(1).get, host._1, host._2)
      }.toSeq
      if (rows.nonEmpty) rows
      else rdd.groupBy(_(1).get).map(row => (row._1, row._2.head)).zipWithIndex.map(row =>
        ServiceRow(traceid, row._1._1, row._1._2(2).get.toLong, row._1._1, "", row._2.toInt)).collect.toSeq
    }
    val list = events.zipWithIndex.map(x => serviceRows(x._2, x._1)).reduce(_ ++ _)
    val tree = list.groupBy(_.trace).mapValues(_.groupBy(_.uuid))
    tree.map(row => row._1 -> new Trace(row._1, row._2))
  }
  private def blacklisted(service: Service): Boolean = removeServices.exists(_.pattern.matcher(service.id).matches)
  val processes: Map[String, Process] = traces.flatMap(trace => trace._2.processes.map(process => process.uuid -> process))
  // FIXME Throughout this file we are making the unsafe assumption that host and port uniquely identify a service
  val services: Map[(String, Int), Service] = processes.flatMap(process => process._2.services.map(service => (service.host, service.port) -> service))
  def trace(id: Int): Trace = traces(id)
  def process(uuid: String): Process = processes(uuid)
  def service(host: (String, Int)): Service = services(host)
  def service(host: String): Service = services(ServiceMap.splitHost(host))
  val mainService: Map[String, Option[Service]] =
    processes.map(process => process._1 -> process._2.services.filter(!blacklisted(_)).sortBy(_.start).headOption)
  def filterRPC(src: String, dst: String): Boolean = !blacklisted(service(src)) && !blacklisted(service(dst))
  def filteredServices: Seq[Service] = services.values.filter(!blacklisted(_)).toSeq.sortBy(svc => (svc.process.trace.id, svc.start))
}
