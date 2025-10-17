import de.fraunhofer.aisec.cpg.frontends.llvm.LLVMIRLanguage
import de.fraunhofer.aisec.cpg.InferenceConfiguration
import de.fraunhofer.aisec.cpg.TranslationConfiguration
import de.fraunhofer.aisec.cpg.TranslationManager
import de.fraunhofer.aisec.cpg_vis_neo4j.Application
import org.neo4j.driver.GraphDatabase
import org.neo4j.ogm.cypher.compiler.builders.node.DefaultNodeBuilder
import org.neo4j.ogm.cypher.compiler.builders.node.DefaultRelationshipBuilder

import java.io.File

/* Excluded nodes */
private val FILTERED_NODES = setOf(
    "GlobalScope"
)

/* Excluded edges */
private val FILTERED_EDGES = setOf(
    "LANGUAGE"
)

/*
* TODO: this can be a CLI similar to cpg-neo4j
* doing something like this will probably be more preferred in terms of installation
* since we don't actually have to build the entire cpg library
*/
fun main() {
    val file = File("main.ll");

    val inferenceConfig = InferenceConfiguration
        .builder()
        .inferRecords(true)
        .build()

    val translationConfig = TranslationConfiguration
        .builder()
        .inferenceConfiguration(inferenceConfig)
        .defaultPasses()
        .registerLanguage<LLVMIRLanguage>()
        .sourceLocations(file)
        .build()

    val result = TranslationManager
        .builder()
        .config(translationConfig)
        .build()
        .analyze()
        .get()

    val application = Application();

    // Actually removing the LANGUAGE edge from within a pass is hell.
    var (nodes, edges) = application.translateCPGToOGMBuilders(result)

    edges = edges?.filter { e -> e.type() !in FILTERED_EDGES }
    nodes = nodes?.filter { n ->
        val node = n.node()
        node.labels.intersect(FILTERED_NODES.toSet()).isEmpty()
    }

    // Move into Neo4J.
    // We can't serialize this back into the translation result nor will I blow myself up
    // trying to read the JSON when I have the actual objects here.
    persist(nodes, edges)
}

/*
* Raw implementation of persisting a modified CPG graph into Neo4j.
* This is only done because the Fraunhofer library doesn't really expose this directly
* nor is there a proper way to convert the graph from the OGM builders back into the translation result.
*
* TODO: odd space between the property "code" and the actual value (ex: code: " %1 = alloca ...")
* TODO: i use cpgId but this is incorrect and doesn't match what the original implementation had
*/
fun persist(
    nodeBuilder: List<DefaultNodeBuilder>?,
    relationshipBuilders: List<DefaultRelationshipBuilder>?,
    uri: String = "bolt://localhost:7687",
    user: String = "neo4j",
    password: String = "00000000"
) {
    val driver = GraphDatabase.driver(uri, org.neo4j.driver.AuthTokens.basic(user, password))
    driver.session().use { session ->
        session.executeWrite { tx ->
            // Nodes from DefaultNodeBuilder
            nodeBuilder?.forEach { builder ->
                val node = builder.node()
                val labels = node.labels.joinToString(":")
                val props = sanitizeProperties(node.propertyList.associate { it.key to it.value }).toMutableMap()
                props["cpgId"] = node.id

                // Run cypher for each node:
                val query = "CREATE (n:$labels) SET n = \$props"
                tx.run(query, mapOf("props" to props))
            }
            relationshipBuilders?.forEach { builder ->
                val rel = builder.edge()
                val relType = rel.type
                val startNodeId = rel.startNode
                val endNodeId = rel.endNode
                val props = sanitizeProperties(rel.propertyList.associate { it.key to it.value })

                val query = """
                    MATCH (start {cpgId: ${'$'}startId})
                    MATCH (end {cpgId: ${'$'}endId})
                    CREATE (start)-[r:$relType]->(end)
                    SET r = ${'$'}props
                """.trimIndent()

                tx.run(query, mapOf(
                    "startId" to startNodeId,
                    "endId" to endNodeId,
                    "props" to props
                ))
            }
        }
    }
}

/*
* Returns Map<String, T> so that every element within the map is converted to a string, integre, or boolean.
*/
fun sanitizeProperties(raw: Map<String, Any?>): Map<String, Any?> {
    return raw.mapValues { (_, v) ->
        when (v) {
            null -> null
            is String, is Number, is Boolean -> v
            is Enum<*> -> v.name
            is Collection<*> -> v.map { it.toString() }
            else -> v.toString()
        }
    }
}

