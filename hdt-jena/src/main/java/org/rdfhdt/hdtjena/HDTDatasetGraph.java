package org.rdfhdt.hdtjena;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.sparql.core.DatasetGraphBase;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.engine.main.QC;
import org.rdfhdt.hdt.hdt.HDT;
import org.rdfhdt.hdt.triples.IteratorTripleID;
import org.rdfhdt.hdt.triples.TripleID;
import org.rdfhdt.hdtjena.solver.HDTJenaQuadIterator;
import org.rdfhdt.hdtjena.solver.HDTQueryEngine;
import org.rdfhdt.hdtjena.solver.OpExecutorHDT;

import java.util.Iterator;

public class HDTDatasetGraph extends DatasetGraphBase {

    private final HDT hdt;

    private final NodeDictionary nodeDictionary;

    //private final ReorderTransformation reorderTransform;

    static {
        // Register OpExecutor
        QC.setFactory(ARQ.getContext(), OpExecutorHDT.opExecFactoryHDT);
        HDTQueryEngine.register();
    }

    public HDTDatasetGraph(HDT hdt) {
        this.hdt = hdt;
        this.nodeDictionary = new NodeDictionary(hdt.getDictionary());
        HDTStatistics hdtStatistics = new HDTStatistics(nodeDictionary, hdt);
        //this.reorderTransform = new ReorderTransformationHDT(this, hdtStatistics);
    }

    @Override
    public Graph getDefaultGraph() {

        return null;
    }

    @Override
    public Graph getGraph(Node node) {
        return null;
    }

    @Override
    public void addGraph(Node node, Graph graph) {

    }

    @Override
    public void removeGraph(Node node) {

    }

    @Override
    public Iterator<Node> listGraphNodes() {
        var graphIterator = hdt.getDictionary().getGraphs().getSortedEntries();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return graphIterator.hasNext();
            }

            @Override
            public Node next() {
                return NodeFactory.createURI(graphIterator.next().toString());
            }
        };
    }

    @Override
    public Iterator<Quad> find(Node graph, Node subject, Node predicate, Node object) {
        System.out.println(graph + " " + subject + " " + predicate  + " " + object);
        TripleID triplePatID = nodeDictionary.getTriplePatID(subject, predicate, object, graph);
        System.out.println(triplePatID);
        IteratorTripleID hdtIterator = hdt.getTriples().search( triplePatID );
        return new HDTJenaQuadIterator(nodeDictionary, hdtIterator);
    }

    @Override
    public Iterator<Quad> findNG(Node graph, Node subject, Node predicate, Node object) {
        TripleID triplePatID = nodeDictionary.getTriplePatID(subject, predicate, object, graph);
        System.out.println("NG");
        System.out.println(triplePatID);
        IteratorTripleID hdtIterator = hdt.getTriples().search( triplePatID );
        return new HDTJenaQuadIterator(nodeDictionary, hdtIterator);
    }

    @Override
    public PrefixMap prefixes() {
        return null;
    }

    @Override
    public boolean supportsTransactions() {
        return false;
    }

    @Override
    public void begin(TxnType txnType) {

    }

    @Override
    public void begin(ReadWrite readWrite) {

    }

    @Override
    public boolean promote(Promote promote) {
        return false;
    }

    @Override
    public void commit() {

    }

    @Override
    public void abort() {

    }

    @Override
    public void end() {

    }

    @Override
    public ReadWrite transactionMode() {
        return null;
    }

    @Override
    public TxnType transactionType() {
        return null;
    }

    @Override
    public boolean isInTransaction() {
        return false;
    }
}
