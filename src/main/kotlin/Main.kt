import de.fraunhofer.aisec.cpg.frontends.llvm.LLVMIRLanguage
import de.fraunhofer.aisec.cpg.InferenceConfiguration
import de.fraunhofer.aisec.cpg.TranslationConfiguration
import de.fraunhofer.aisec.cpg.TranslationManager
import de.fraunhofer.aisec.cpg_vis_neo4j.Application
import org.neo4j.driver.GraphDatabase
import org.neo4j.ogm.cypher.compiler.builders.node.DefaultNodeBuilder
import org.neo4j.ogm.cypher.compiler.builders.node.DefaultRelationshipBuilder

import java.io.File

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
    edges = edges?.filter { e -> e.type() != "LANGUAGE" }

    // Move into Neo4J.
    // We can't serialize this back into the translation result nor will I blow myself up
    // trying to read the JSON when I have the actual objects here.
    persist(nodes, edges)
}

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
                // Following the regular id will break because they are negative numbers..? for some reason.
                props["cpgId"] = node.id

                // Run cypher for each node:
                println(props);
                tx.run("CREATE (n:$labels)")
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

