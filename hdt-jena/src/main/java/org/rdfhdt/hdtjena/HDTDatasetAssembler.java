package org.rdfhdt.hdtjena;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.assembler.assemblers.AssemblerBase;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.util.graph.GraphUtils;
import org.rdfhdt.hdt.hdt.HDT;
import org.rdfhdt.hdt.hdt.HDTManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class HDTDatasetAssembler extends AssemblerBase implements Assembler  {

    private static final Logger log = LoggerFactory.getLogger(HDTDatasetAssembler.class);

    @Override
    public Dataset open(Assembler a, Resource root, Mode mode) {
        String file = GraphUtils.getStringValue(root, HDTJenaConstants.pFileName) ;
        boolean loadInMemory = Boolean.parseBoolean(GraphUtils.getStringValue(root, HDTJenaConstants.pKeepInMemory));
        HDT hdt;
        try {
            if (loadInMemory) {
                hdt = HDTManager.loadIndexedHDT(file);
            } else {
                hdt = HDTManager.mapIndexedHDT(file);
            }
            HDTDatasetGraph dataset = new HDTDatasetGraph(hdt);
            DatasetFactory.wrap(dataset);
        } catch (IOException e) {
            log.error("Error reading HDT file: {}", file, e);
            throw new AssemblerException(root, "Error reading HDT file: "+file+" / "+ e);
        }
        return null;
    }
}
