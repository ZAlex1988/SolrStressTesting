package com.solr.test;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.slf4j.Log4jLoggerFactory;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.zookeeper.proto.DeleteRequest;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class InsertDocs {
    private static final Logger log = LogManager.getLogger("appLogger");

    private CloudSolrClient solr;
    private List<SolrInputDocument> allDocs = new ArrayList<>();
    private Random rand = new Random();
    private String fileName = "C:\\FOSS\\solr-8.1.1\\solr-8.1.1\\example\\films\\films.csv";

    private boolean parallelDone;
    private long parallelStartTime;
    private long parallelTotalTime;

    private String user;
    private String pass;

    public InsertDocs(String user, String pass) {
        this.user = user;
        this.pass = pass;
        log.info("Creating SolrClient...");
        setSolr();
        log.info("Client created - Success! Start Reading file...");
        List<String> fileContents = readCsvIntoMemory(fileName);
        mapSolrDocs(fileContents);

    }

    private void mapSolrDocs(List<String> fileContents) {
        log.info("Begin mapping documents..");
        fileContents.forEach(rec -> {
            SolrInputDocument doc = new SolrInputDocument();
            String [] recContents = rec.split(",");
            List<String> directedBy = Arrays.asList(recContents[1].split("\\|"));
            List<String> genre = Arrays.asList(recContents[2].split("\\|"));
            String date = recContents[5].trim() + "T00:00:00Z";

            if (!"".equalsIgnoreCase(recContents[0].trim()))
                doc.addField("name", recContents[0].trim());
            if (directedBy.size() > 0)
                doc.addField("directed_by", directedBy);
            if (genre.size() > 0)
                doc.addField("genre", genre);
            if (!"".equalsIgnoreCase(recContents[5].trim()))
                doc.addField("initial_release_date", date);

            doc.addField("numActors", rand.nextInt(200));

            allDocs.add(doc);

        });
        log.info("Done mapping documents..");
    }

    private void setSolr() {
        List<String> zkHostUrl = Arrays.asList("localhost:9983");
        Optional<String> chRoot = Optional.empty();
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set(HttpClientUtil.PROP_BASIC_AUTH_USER, this.user);
        params.set(HttpClientUtil.PROP_BASIC_AUTH_PASS, this.pass);
        CloseableHttpClient httpClient = HttpClientUtil.createClient(params);
        solr = new CloudSolrClient.Builder(zkHostUrl, chRoot).withHttpClient(httpClient).build();
        solr.connect();
    }

    private List<String> readCsvIntoMemory(String fileName) {
        List<String> fileContents = new ArrayList<>();
        try {
            log.info("reading file.. " + fileName);
            fileContents = new ArrayList<>(Arrays.asList(new String (Files.readAllBytes(Paths.get(fileName)))
                    .replace("\"","").split("\n")));
            log.info("Done reading file: " + fileContents.size() + " lines read in memory. Sample file lines: ");
            fileContents.remove(0); // remove headers
            //show records
//            for (int i = 0; i < 10; i++) {
//                log.info((i + 1) + " record: " + fileContents.get(i));
//            }

        } catch (IOException e) {
            log.error("Error reading file " + fileName, e);
        }
        return fileContents;
    }

    private void createCollection() {
        String configDir = "C:\\FOSS\\solr-8.1.1\\solr-8.1.1\\server\\solr\\configsets\\Movies\\conf";
        try {
            solr.getZkStateReader().getConfigManager().uploadConfigDir(Paths.get(configDir), "Movies");
            CollectionAdminRequest.createCollection("Movies", "Movies", 1, 1)
                    .setMaxShardsPerNode(1).processAndWait(solr, 20);
        } catch (IOException e) {
            log.error("Error uploading config directory to Zoo: ", e);
        } catch (SolrServerException | InterruptedException e) {
            log.error("Unable to create collection: ", e);
        }
    }

    private void deleteCollection() {
        try {
            CollectionAdminRequest.deleteCollection("Movies").processAndWait(solr, 20);
        }  catch (SolrServerException | InterruptedException | IOException e) {
            log.error("Unable to create collection: ", e);
        }
    }

    public void clearCollection() {
        try {
            UpdateRequest ur = new UpdateRequest();
            ur.deleteByQuery("*:*");
            ur.setBasicAuthCredentials(this.user, this.pass);
            ur.process(solr, "Movies");
            solr.commit("Movies");
        }  catch (SolrServerException | IOException e) {
            log.error("Unable to clear collection: ", e);
        }
    }

    public void insertBatch() {
        UpdateRequest ur = new UpdateRequest();
        ur.add(allDocs);
        ur.setBasicAuthCredentials(this.user, this.pass);
        try {
            ur.process(solr, "Movies");
            solr.commit("Movies");
        } catch (SolrServerException | IOException e) {
            log.error("Unable to add document: ", e);
        }
    }

    public void closeSolrClient() {
        try {
            solr.close();
        } catch (IOException e) {
            log.error("Unable to close Solr client: ", e);
        }
    }

    public CloudSolrClient getSolr() {
        return solr;
    }

    public void asyncInsert(int numDocsToInsert) {
        int batches = numDocsToInsert / 1000;
        log.info("Total batches to insert: " + batches);
        this.parallelStartTime = System.currentTimeMillis();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            for (int i = 0; i < batches ; i++) {
                this.insertBatch();
                log.debug("Batched inserted so far: " + (i+1));
            }
            this.parallelDone = true;
            this.parallelTotalTime = System.currentTimeMillis() - this.parallelStartTime;
        });
    }

    public boolean isParallelDone() {
        return parallelDone;
    }

    //returns time is seconds for async insert
    public long getParallelTotalTime() {
        return (parallelTotalTime/1000);
    }
}
