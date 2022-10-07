package graphql.schema.diffing;

import graphql.schema.GraphQLSchema;
import graphql.schema.diffing.ana.EditOperationAnalyzer;
import graphql.schema.diffing.ana.SchemaChange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static graphql.Assert.assertTrue;
import static graphql.schema.diffing.EditorialCostForMapping.editorialCostForMapping;
import static java.util.Collections.singletonList;

public class SchemaDiffing {


    SchemaGraph sourceGraph;
    SchemaGraph targetGraph;

    public List<EditOperation> diffGraphQLSchema(GraphQLSchema graphQLSchema1, GraphQLSchema graphQLSchema2) throws Exception {
        sourceGraph = new SchemaGraphFactory("source-").createGraph(graphQLSchema1);
        targetGraph = new SchemaGraphFactory("target-").createGraph(graphQLSchema2);
        return diffImpl(sourceGraph, targetGraph).listOfEditOperations.get(0);
    }

    public List<SchemaChange> diffAndAnalyze(GraphQLSchema graphQLSchema1, GraphQLSchema graphQLSchema2) throws Exception {
        sourceGraph = new SchemaGraphFactory("source-").createGraph(graphQLSchema1);
        targetGraph = new SchemaGraphFactory("target-").createGraph(graphQLSchema2);
        DiffImpl.OptimalEdit optimalEdit = diffImpl(sourceGraph, targetGraph);
        EditOperationAnalyzer editOperationAnalyzer = new EditOperationAnalyzer(graphQLSchema1, graphQLSchema1, sourceGraph, targetGraph);
        return editOperationAnalyzer.analyzeEdits(optimalEdit.listOfEditOperations.get(0));
    }

    public DiffImpl.OptimalEdit diffGraphQLSchemaAllEdits(GraphQLSchema graphQLSchema1, GraphQLSchema graphQLSchema2) throws Exception {
        sourceGraph = new SchemaGraphFactory("source-").createGraph(graphQLSchema1);
        targetGraph = new SchemaGraphFactory("target-").createGraph(graphQLSchema2);
        return diffImpl(sourceGraph, targetGraph);
    }


    private DiffImpl.OptimalEdit diffImpl(SchemaGraph sourceGraph, SchemaGraph targetGraph) throws Exception {
        int sizeDiff = targetGraph.size() - sourceGraph.size();
        System.out.println("graph diff: " + sizeDiff);
        FillupIsolatedVertices fillupIsolatedVertices = new FillupIsolatedVertices(sourceGraph, targetGraph);
        fillupIsolatedVertices.ensureGraphAreSameSize();
        FillupIsolatedVertices.IsolatedVertices isolatedVertices = fillupIsolatedVertices.isolatedVertices;

        assertTrue(sourceGraph.size() == targetGraph.size());
//        if (sizeDiff != 0) {
//            SortSourceGraph.sortSourceGraph(sourceGraph, targetGraph, isolatedVertices);
//        }
        Mapping fixedMappings = isolatedVertices.mapping;
        System.out.println("fixed mappings: " + fixedMappings.size() + " vs " + sourceGraph.size());
        if (fixedMappings.size() == sourceGraph.size()) {
            List<EditOperation> result = new ArrayList<>();
            editorialCostForMapping(fixedMappings, sourceGraph, targetGraph, result);
            return new DiffImpl.OptimalEdit(singletonList(fixedMappings), singletonList(result), result.size());
        }
        DiffImpl diffImpl = new DiffImpl(sourceGraph, targetGraph, isolatedVertices);
        List<Vertex> nonMappedSource = new ArrayList<>(sourceGraph.getVertices());
        nonMappedSource.removeAll(fixedMappings.getSources());
//        for(Vertex vertex: nonMappedSource) {
//            System.out.println("non mapped: " + vertex);
//        }
//        for (List<String> context : isolatedVertices.contexts.rowKeySet()) {
//            Map<Set<Vertex>, Set<Vertex>> row = isolatedVertices.contexts.row(context);
//            System.out.println("context: " + context + " from " + row.keySet().iterator().next().size() + " to " + row.values().iterator().next().size());
//        }

        List<Vertex> nonMappedTarget = new ArrayList<>(targetGraph.getVertices());
        nonMappedTarget.removeAll(fixedMappings.getTargets());

        sortListBasedOnPossibleMapping(nonMappedSource, isolatedVertices);

        // the non mapped vertices go to the end
        List<Vertex> sourceVertices = new ArrayList<>();
        sourceVertices.addAll(fixedMappings.getSources());
        sourceVertices.addAll(nonMappedSource);

        List<Vertex> targetGraphVertices = new ArrayList<>();
        targetGraphVertices.addAll(fixedMappings.getTargets());
        targetGraphVertices.addAll(nonMappedTarget);


        DiffImpl.OptimalEdit optimalEdit = diffImpl.diffImpl(fixedMappings, sourceVertices, targetGraphVertices);
//        System.out.println("different edit counts: " + optimalEdit.listOfEditOperations.size());
//        for (int i = 0; i < optimalEdit.listOfEditOperations.size(); i++) {
//            System.out.println("--------------");
//            System.out.println("edit: " + i);
//            System.out.println("--------------");
//            for (EditOperation editOperation : optimalEdit.listOfEditOperations.get(i)) {
//                System.out.println(editOperation);
//            }
//            System.out.println("--------------");
//            System.out.println("--------------");
//        }
        return optimalEdit;
    }

    private void sortListBasedOnPossibleMapping(List<Vertex> sourceVertices, FillupIsolatedVertices.IsolatedVertices isolatedVertices) {
        Collections.sort(sourceVertices, (v1, v2) ->
        {
            int v2Count = isolatedVertices.possibleMappings.get(v2).size();
            int v1Count = isolatedVertices.possibleMappings.get(v1).size();
            return Integer.compare(v2Count, v1Count);
        });

//        for (Vertex vertex : sourceGraph.getVertices()) {
//            System.out.println("c: " + isolatedVertices.possibleMappings.get(vertex).size() + " v: " + vertex);
//        }
    }


    private List<EditOperation> calcEdgeOperations(Mapping mapping) {
        List<Edge> edges = sourceGraph.getEdges();
        List<EditOperation> result = new ArrayList<>();
        // edge deletion or relabeling
        for (Edge sourceEdge : edges) {
            Vertex target1 = mapping.getTarget(sourceEdge.getFrom());
            Vertex target2 = mapping.getTarget(sourceEdge.getTo());
            Edge targetEdge = targetGraph.getEdge(target1, target2);
            if (targetEdge == null) {
                result.add(EditOperation.deleteEdge("Delete edge " + sourceEdge, sourceEdge));
            } else if (!sourceEdge.getLabel().equals(targetEdge.getLabel())) {
                result.add(EditOperation.changeEdge("Change " + sourceEdge + " to " + targetEdge, sourceEdge, targetEdge));
            }
        }

        //TODO: iterates over all edges in the target Graph
        for (Edge targetEdge : targetGraph.getEdges()) {
            // only subgraph edges
            Vertex sourceFrom = mapping.getSource(targetEdge.getFrom());
            Vertex sourceTo = mapping.getSource(targetEdge.getTo());
            if (sourceGraph.getEdge(sourceFrom, sourceTo) == null) {
                result.add(EditOperation.insertEdge("Insert edge " + targetEdge, targetEdge));
            }
        }
        return result;
    }


//        List<String> debugMap = getDebugMap(bestFullMapping.get());
//        for (String debugLine : debugMap) {
//            System.out.println(debugLine);
//        }
//        System.out.println("edit : " + bestEdit);
//        for (EditOperation editOperation : bestEdit.get()) {
//            System.out.println(editOperation);
//        }
//    private List<EditOperation> diffImplImpl() {

//    private void logUnmappable(AtomicDoubleArray[] costMatrix, int[] assignments, List<Vertex> sourceList, ArrayList<Vertex> availableTargetVertices, int level) {
//        for (int i = 0; i < assignments.length; i++) {
//            double value = costMatrix[i].get(assignments[i]);
//            if (value >= Integer.MAX_VALUE) {
//                System.out.println("i " + i + " can't mapped");
//                Vertex v = sourceList.get(i + level - 1);
//                Vertex u = availableTargetVertices.get(assignments[i]);
//                System.out.println("from " + v + " to " + u);
//            }
//        }
//    }
//
//
//    // minimum number of edit operations for a full mapping
//

}
