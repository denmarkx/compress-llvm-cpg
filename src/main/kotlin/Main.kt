import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.fraunhofer.aisec.cpg.frontends.llvm.LLVMIRLanguage
import de.fraunhofer.aisec.cpg.InferenceConfiguration
import de.fraunhofer.aisec.cpg.TranslationConfiguration
import de.fraunhofer.aisec.cpg.TranslationManager
import de.fraunhofer.aisec.cpg_vis_neo4j.Application
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

    val graph = application.buildJsonGraph(nodes, edges)

    // Not really needing to do this, but I don't wanna export to Neo4j everytime.
    val mapper = jacksonObjectMapper()
    val jsonString = mapper.writeValueAsString(graph)
    File("graph.json").writeText(jsonString)
}

