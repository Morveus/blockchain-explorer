package models;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.ResourceIterator;
//import org.neo4j.helpers.collection.IteratorUtil;

public class EmbeddedNeo4j
{
    public static void insertBlock(GraphDatabaseService graphDb, String blockQuery, String blockHash, Long blockHeight, String unclesQuery, List<String> txQueries)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            ResourceIterator<Node> resultIterator = graphDb.execute( blockQuery ).columnAs( "b" );
            Node node = resultIterator.next();

            nodeIndex.add( node, "hash", blockHash );
            nodeIndex.add( node, "height", blockHeight );

            if(unclesQuery != ""){
                graphDb.execute( unclesQuery );
            }

            for (String txQuery : txQueries) { 
               graphDb.execute( txQuery );
            }
            

            tx.success();
            tx.close();
        }
    }

    /*
    public static void insertTransaction(GraphDatabaseService graphDb, String queryString, String txHash)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            ResourceIterator<Node> resultIterator = graphDb.execute( queryString ).columnAs( "tx" );
            Node node = resultIterator.next();

            nodeIndex.add( node, "hash", txHash );

            tx.success();
        }
    }

    public static void completeProcessTransaction(GraphDatabaseService graphDb, String txHash)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            String queryString = "MATCH (tx:Transaction { hash: '"+txHash+"'}) SET tx.to_process = 0";
            graphDb.execute( queryString );

            tx.success();
        }
    }

    public static Boolean insertInOutput(GraphDatabaseService graphDb, String queryString)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            graphDb.execute( queryString );
            tx.success();

            return true;
        }
    }

    public static List<String> getUnprocessedTransactions(GraphDatabaseService graphDb, String noHashes, Integer limit)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            List<String> hashes = new ArrayList<String>();
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            String queryString = "MATCH (tx:Transaction) WHERE tx.to_process = 1 AND NOT(tx.hash IN "+noHashes+") RETURN tx LIMIT "+limit;
            ResourceIterator<Node> resultIterator = graphDb.execute( queryString ).columnAs( "tx" );
            for (Node node : IteratorUtil.asIterable(resultIterator)) {
                hashes.add(node.getProperty( "hash" ).toString());
            }

            return hashes;
        }

    }

    public static String getBeforeLastBlockHash(GraphDatabaseService graphDb)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            String queryString = "MATCH (b:Block) RETURN b ORDER BY b.height DESC SKIP 1 LIMIT 1";
            ResourceIterator<Node> resultIterator = graphDb.execute( queryString ).columnAs( "b" );
            Node node = resultIterator.next();

            String hash = (String) node.getProperty("hash");
            return hash;
        }

    }

    */
    

}