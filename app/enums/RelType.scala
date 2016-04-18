package enums

import org.neo4j.graphdb._

object RelTypes extends Enumeration {
  type RelTypes = Value
  val USERS_REFERENCE, USER = Value

  implicit def conv(rt: RelTypes) = new RelationshipType() {def name = rt.toString}
}