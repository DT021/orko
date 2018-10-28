package com.grahamcrockford.orko.db;

import java.util.function.Supplier;

import org.mongojack.DBQuery;
import org.mongojack.JacksonDBCollection;

import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.grahamcrockford.orko.spi.Job;
import com.grahamcrockford.orko.submit.JobAccess;
import com.grahamcrockford.orko.submit.JobLocker;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoClient;


/**
 * Direct access to the data store.
 */
@Singleton
class MongoJobAccess implements JobAccess {

  private static final String ID_FIELD = "_id";
  private static final String PROCESSED_FIELD = "processed";

  private final Supplier<JacksonDBCollection<Envelope, String>> collection = Suppliers.memoize(this::collection);

  private final MongoClient mongoClient;
  private final JobLocker jobLocker;
  private final DbConfiguration configuration;

  @Inject
  MongoJobAccess(MongoClient mongoClient, JobLocker jobLocker, DbConfiguration configuration) {
    this.mongoClient = mongoClient;
    this.jobLocker = jobLocker;
    this.configuration = configuration;
  }

  /**
   * @see com.grahamcrockford.orko.submit.JobAccess#insert(com.grahamcrockford.orko.spi.Job)
   */
  @Override
  public void insert(Job job) throws JobAlreadyExistsException {
    try {
      collection.get().insert(Envelope.live(job));
    } catch (DuplicateKeyException e) {
      throw new JobAlreadyExistsException(e);
    }
  }

  /**
   * @see com.grahamcrockford.orko.submit.JobAccess#update(com.grahamcrockford.orko.spi.Job)
   */
  @Override
  public void update(Job job) {
    collection.get().update(DBQuery.is(ID_FIELD, job.id()), Envelope.live(job));
  }

  /**
   * @see com.grahamcrockford.orko.submit.JobAccess#load(java.lang.String)
   */
  @Override
  public Job load(String id) {
    Envelope envelope = collection.get().findOneById(id);
    if (envelope == null)
      throw new JobDoesNotExistException();
    if (envelope.job() == null)
      throw new JobDoesNotExistException();
    return envelope.job();
  }

  /**
   * @see com.grahamcrockford.orko.submit.JobAccess#list()
   */
  @Override
  public Iterable<Job> list() {
    return FluentIterable.from(collection.get().find(DBQuery.is(PROCESSED_FIELD, false))).transform(Envelope::job);
  }

  /**
   * @see com.grahamcrockford.orko.submit.JobAccess#delete(java.lang.String)
   */
  @Override
  public void delete(String jobId) {
    collection.get().update(DBQuery.is(ID_FIELD, jobId), Envelope.dead(jobId));
    jobLocker.releaseAnyLock(jobId);
  }

  /**
   * @see com.grahamcrockford.orko.submit.JobAccess#deleteAll()
   */
  @Override
  public void deleteAll() {
    collection.get().update(
      new BasicDBObject().append(PROCESSED_FIELD, false),
      new BasicDBObject().append("job", null).append(PROCESSED_FIELD, true)
    );
    jobLocker.releaseAllLocks();
  }

  private JacksonDBCollection<Envelope, String> collection() {
    DBCollection result = mongoClient.getDB(configuration.getMongoDatabase()).getCollection("job");
    createProcessedIndex(result);
    return JacksonDBCollection.wrap(result, Envelope.class, String.class);
  }

  private void createProcessedIndex(DBCollection collection) {
    BasicDBObject index = new BasicDBObject();
    index.put(PROCESSED_FIELD, -1);
    index.put(ID_FIELD, 1);
    BasicDBObject indexOpts = new BasicDBObject();
    indexOpts.put("unique", false);
    collection.createIndex(index, indexOpts);
  }
}