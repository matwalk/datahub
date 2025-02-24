package com.linkedin.metadata.search;

import static com.linkedin.metadata.Constants.ELASTICSEARCH_IMPLEMENTATION_ELASTICSEARCH;
import static io.datahubproject.test.search.SearchTestUtils.syncAfterWrite;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.datahub.test.Snapshot;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.linkedin.common.urn.TestEntityUrn;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.StringArray;
import com.linkedin.metadata.aspect.AspectRetriever;
import com.linkedin.metadata.config.cache.EntityDocCountCacheConfiguration;
import com.linkedin.metadata.config.search.SearchConfiguration;
import com.linkedin.metadata.config.search.custom.CustomSearchConfiguration;
import com.linkedin.metadata.models.registry.SnapshotEntityRegistry;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.Condition;
import com.linkedin.metadata.query.filter.ConjunctiveCriterion;
import com.linkedin.metadata.query.filter.ConjunctiveCriterionArray;
import com.linkedin.metadata.query.filter.Criterion;
import com.linkedin.metadata.query.filter.CriterionArray;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.search.cache.EntityDocCountCache;
import com.linkedin.metadata.search.client.CachingEntitySearchService;
import com.linkedin.metadata.search.elasticsearch.ElasticSearchService;
import com.linkedin.metadata.search.elasticsearch.indexbuilder.ESIndexBuilder;
import com.linkedin.metadata.search.elasticsearch.indexbuilder.EntityIndexBuilders;
import com.linkedin.metadata.search.elasticsearch.indexbuilder.SettingsBuilder;
import com.linkedin.metadata.search.elasticsearch.query.ESBrowseDAO;
import com.linkedin.metadata.search.elasticsearch.query.ESSearchDAO;
import com.linkedin.metadata.search.elasticsearch.update.ESBulkProcessor;
import com.linkedin.metadata.search.elasticsearch.update.ESWriteDAO;
import com.linkedin.metadata.search.ranker.SimpleRanker;
import com.linkedin.metadata.utils.elasticsearch.IndexConvention;
import com.linkedin.metadata.utils.elasticsearch.IndexConventionImpl;
import com.linkedin.r2.RemoteInvocationException;
import java.net.URISyntaxException;
import java.util.Map;
import javax.annotation.Nonnull;
import org.opensearch.client.RestHighLevelClient;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public abstract class SearchServiceTestBase extends AbstractTestNGSpringContextTests {

  @Nonnull
  protected abstract RestHighLevelClient getSearchClient();

  @Nonnull
  protected abstract ESBulkProcessor getBulkProcessor();

  @Nonnull
  protected abstract ESIndexBuilder getIndexBuilder();

  @Nonnull
  protected abstract SearchConfiguration getSearchConfiguration();

  @Nonnull
  protected abstract CustomSearchConfiguration getCustomSearchConfiguration();

  private AspectRetriever aspectRetriever;
  private IndexConvention indexConvention;
  private SettingsBuilder settingsBuilder;
  private ElasticSearchService elasticSearchService;
  private CacheManager cacheManager;
  private SearchService searchService;

  private static final String ENTITY_NAME = "testEntity";

  @BeforeClass
  public void setup() throws RemoteInvocationException, URISyntaxException {
    aspectRetriever = mock(AspectRetriever.class);
    when(aspectRetriever.getEntityRegistry())
        .thenReturn(new SnapshotEntityRegistry(new Snapshot()));
    when(aspectRetriever.getLatestAspectObjects(any(), any())).thenReturn(Map.of());
    indexConvention = new IndexConventionImpl("search_service_test");
    settingsBuilder = new SettingsBuilder(null);
    elasticSearchService = buildEntitySearchService();
    elasticSearchService.configure();
    cacheManager = new ConcurrentMapCacheManager();
    resetSearchService();
  }

  private void resetSearchService() {
    CachingEntitySearchService cachingEntitySearchService =
        new CachingEntitySearchService(cacheManager, elasticSearchService, 100, true);

    EntityDocCountCacheConfiguration entityDocCountCacheConfiguration =
        new EntityDocCountCacheConfiguration();
    entityDocCountCacheConfiguration.setTtlSeconds(600L);
    searchService =
        new SearchService(
            new EntityDocCountCache(
                aspectRetriever.getEntityRegistry(),
                elasticSearchService,
                entityDocCountCacheConfiguration),
            cachingEntitySearchService,
            new SimpleRanker());
  }

  @BeforeMethod
  public void wipe() throws Exception {
    elasticSearchService.clear();
    syncAfterWrite(getBulkProcessor());
  }

  @Nonnull
  private ElasticSearchService buildEntitySearchService() {
    EntityIndexBuilders indexBuilders =
        new EntityIndexBuilders(
            getIndexBuilder(),
            aspectRetriever.getEntityRegistry(),
            indexConvention,
            settingsBuilder);
    ESSearchDAO searchDAO =
        new ESSearchDAO(
            getSearchClient(),
            indexConvention,
            false,
            ELASTICSEARCH_IMPLEMENTATION_ELASTICSEARCH,
            getSearchConfiguration(),
            null);
    ESBrowseDAO browseDAO =
        new ESBrowseDAO(
            getSearchClient(),
            indexConvention,
            getSearchConfiguration(),
            getCustomSearchConfiguration());
    ESWriteDAO writeDAO =
        new ESWriteDAO(
            aspectRetriever.getEntityRegistry(),
            getSearchClient(),
            indexConvention,
            getBulkProcessor(),
            1);
    return new ElasticSearchService(indexBuilders, searchDAO, browseDAO, writeDAO)
        .postConstruct(aspectRetriever);
  }

  private void clearCache() {
    cacheManager.getCacheNames().forEach(cache -> cacheManager.getCache(cache).clear());
    resetSearchService();
  }

  @Test
  public void testSearchService() throws Exception {
    SearchResult searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(ENTITY_NAME),
            "test",
            null,
            null,
            0,
            10,
            new SearchFlags().setFulltext(true).setSkipCache(true));
    assertEquals(searchResult.getNumEntities().intValue(), 0);
    searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(), "test", null, null, 0, 10, new SearchFlags().setFulltext(true));
    assertEquals(searchResult.getNumEntities().intValue(), 0);
    clearCache();

    Urn urn = new TestEntityUrn("test", "urn1", "VALUE_1");
    ObjectNode document = JsonNodeFactory.instance.objectNode();
    document.set("urn", JsonNodeFactory.instance.textNode(urn.toString()));
    document.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    elasticSearchService.upsertDocument(ENTITY_NAME, document.toString(), urn.toString());
    syncAfterWrite(getBulkProcessor());

    searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(), "test", null, null, 0, 10, new SearchFlags().setFulltext(true));
    assertEquals(searchResult.getNumEntities().intValue(), 1);
    assertEquals(searchResult.getEntities().get(0).getEntity(), urn);
    clearCache();

    Urn urn2 = new TestEntityUrn("test2", "urn2", "VALUE_2");
    ObjectNode document2 = JsonNodeFactory.instance.objectNode();
    document2.set("urn", JsonNodeFactory.instance.textNode(urn2.toString()));
    document2.set("keyPart1", JsonNodeFactory.instance.textNode("random"));
    document2.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride2"));
    document2.set("browsePaths", JsonNodeFactory.instance.textNode("/b/c"));
    elasticSearchService.upsertDocument(ENTITY_NAME, document2.toString(), urn2.toString());
    syncAfterWrite(getBulkProcessor());

    searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(), "'test2'", null, null, 0, 10, new SearchFlags().setFulltext(true));
    assertEquals(searchResult.getNumEntities().intValue(), 1);
    assertEquals(searchResult.getEntities().get(0).getEntity(), urn2);
    clearCache();

    long docCount = elasticSearchService.docCount(ENTITY_NAME);
    assertEquals(docCount, 2L);

    elasticSearchService.deleteDocument(ENTITY_NAME, urn.toString());
    elasticSearchService.deleteDocument(ENTITY_NAME, urn2.toString());
    syncAfterWrite(getBulkProcessor());
    searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(), "'test2'", null, null, 0, 10, new SearchFlags().setFulltext(true));
    assertEquals(searchResult.getNumEntities().intValue(), 0);
  }

  @Test
  public void testAdvancedSearchOr() throws Exception {
    final Criterion filterCriterion =
        new Criterion()
            .setField("platform")
            .setCondition(Condition.EQUAL)
            .setValue("hive")
            .setValues(new StringArray(ImmutableList.of("hive")));

    final Criterion subtypeCriterion =
        new Criterion()
            .setField("subtypes")
            .setCondition(Condition.EQUAL)
            .setValue("")
            .setValues(new StringArray(ImmutableList.of("view")));

    final Filter filterWithCondition =
        new Filter()
            .setOr(
                new ConjunctiveCriterionArray(
                    new ConjunctiveCriterion()
                        .setAnd(new CriterionArray(ImmutableList.of(filterCriterion))),
                    new ConjunctiveCriterion()
                        .setAnd(new CriterionArray(ImmutableList.of(subtypeCriterion)))));

    SearchResult searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(ENTITY_NAME),
            "test",
            filterWithCondition,
            null,
            0,
            10,
            new SearchFlags().setFulltext(true));

    assertEquals(searchResult.getNumEntities().intValue(), 0);
    clearCache();

    Urn urn = new TestEntityUrn("test", "testUrn", "VALUE_1");
    ObjectNode document = JsonNodeFactory.instance.objectNode();
    document.set("urn", JsonNodeFactory.instance.textNode(urn.toString()));
    document.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document.set("subtypes", JsonNodeFactory.instance.textNode("view"));
    document.set("platform", JsonNodeFactory.instance.textNode("snowflake"));
    elasticSearchService.upsertDocument(ENTITY_NAME, document.toString(), urn.toString());

    Urn urn2 = new TestEntityUrn("test", "testUrn", "VALUE_2");
    ObjectNode document2 = JsonNodeFactory.instance.objectNode();
    document2.set("urn", JsonNodeFactory.instance.textNode(urn2.toString()));
    document2.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document2.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document2.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document2.set("subtypes", JsonNodeFactory.instance.textNode("table"));
    document2.set("platform", JsonNodeFactory.instance.textNode("hive"));
    elasticSearchService.upsertDocument(ENTITY_NAME, document2.toString(), urn2.toString());

    Urn urn3 = new TestEntityUrn("test", "testUrn", "VALUE_3");
    ObjectNode document3 = JsonNodeFactory.instance.objectNode();
    document3.set("urn", JsonNodeFactory.instance.textNode(urn3.toString()));
    document3.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document3.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document3.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document3.set("subtypes", JsonNodeFactory.instance.textNode("table"));
    document3.set("platform", JsonNodeFactory.instance.textNode("snowflake"));
    elasticSearchService.upsertDocument(ENTITY_NAME, document3.toString(), urn3.toString());

    syncAfterWrite(getBulkProcessor());

    searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(),
            "test",
            filterWithCondition,
            null,
            0,
            10,
            new SearchFlags().setFulltext(true));
    assertEquals(searchResult.getNumEntities().intValue(), 2);
    assertEquals(searchResult.getEntities().get(0).getEntity(), urn);
    assertEquals(searchResult.getEntities().get(1).getEntity(), urn2);
    clearCache();
  }

  @Test
  public void testAdvancedSearchSoftDelete() throws Exception {
    final Criterion filterCriterion =
        new Criterion()
            .setField("platform")
            .setCondition(Condition.EQUAL)
            .setValue("hive")
            .setValues(new StringArray(ImmutableList.of("hive")));

    final Criterion removedCriterion =
        new Criterion()
            .setField("removed")
            .setCondition(Condition.EQUAL)
            .setValue("")
            .setValues(new StringArray(ImmutableList.of("true")));

    final Filter filterWithCondition =
        new Filter()
            .setOr(
                new ConjunctiveCriterionArray(
                    new ConjunctiveCriterion()
                        .setAnd(
                            new CriterionArray(
                                ImmutableList.of(filterCriterion, removedCriterion)))));

    SearchResult searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(ENTITY_NAME),
            "test",
            filterWithCondition,
            null,
            0,
            10,
            new SearchFlags().setFulltext(true));

    assertEquals(searchResult.getNumEntities().intValue(), 0);
    clearCache();

    Urn urn = new TestEntityUrn("test", "testUrn", "VALUE_1");
    ObjectNode document = JsonNodeFactory.instance.objectNode();
    document.set("urn", JsonNodeFactory.instance.textNode(urn.toString()));
    document.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document.set("subtypes", JsonNodeFactory.instance.textNode("view"));
    document.set("platform", JsonNodeFactory.instance.textNode("hive"));
    document.set("removed", JsonNodeFactory.instance.booleanNode(true));
    elasticSearchService.upsertDocument(ENTITY_NAME, document.toString(), urn.toString());

    Urn urn2 = new TestEntityUrn("test", "testUrn", "VALUE_2");
    ObjectNode document2 = JsonNodeFactory.instance.objectNode();
    document2.set("urn", JsonNodeFactory.instance.textNode(urn2.toString()));
    document2.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document2.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document2.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document2.set("subtypes", JsonNodeFactory.instance.textNode("table"));
    document2.set("platform", JsonNodeFactory.instance.textNode("hive"));
    document.set("removed", JsonNodeFactory.instance.booleanNode(false));
    elasticSearchService.upsertDocument(ENTITY_NAME, document2.toString(), urn2.toString());

    Urn urn3 = new TestEntityUrn("test", "testUrn", "VALUE_3");
    ObjectNode document3 = JsonNodeFactory.instance.objectNode();
    document3.set("urn", JsonNodeFactory.instance.textNode(urn3.toString()));
    document3.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document3.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document3.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document3.set("subtypes", JsonNodeFactory.instance.textNode("table"));
    document3.set("platform", JsonNodeFactory.instance.textNode("snowflake"));
    document.set("removed", JsonNodeFactory.instance.booleanNode(false));
    elasticSearchService.upsertDocument(ENTITY_NAME, document3.toString(), urn3.toString());

    syncAfterWrite(getBulkProcessor());

    searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(),
            "test",
            filterWithCondition,
            null,
            0,
            10,
            new SearchFlags().setFulltext(true));
    assertEquals(searchResult.getNumEntities().intValue(), 1);
    assertEquals(searchResult.getEntities().get(0).getEntity(), urn);
    clearCache();
  }

  @Test
  public void testAdvancedSearchNegated() throws Exception {
    final Criterion filterCriterion =
        new Criterion()
            .setField("platform")
            .setCondition(Condition.EQUAL)
            .setValue("hive")
            .setNegated(true)
            .setValues(new StringArray(ImmutableList.of("hive")));

    final Filter filterWithCondition =
        new Filter()
            .setOr(
                new ConjunctiveCriterionArray(
                    new ConjunctiveCriterion()
                        .setAnd(new CriterionArray(ImmutableList.of(filterCriterion)))));

    SearchResult searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(ENTITY_NAME),
            "test",
            filterWithCondition,
            null,
            0,
            10,
            new SearchFlags().setFulltext(true));

    assertEquals(searchResult.getNumEntities().intValue(), 0);
    clearCache();

    Urn urn = new TestEntityUrn("test", "testUrn", "VALUE_1");
    ObjectNode document = JsonNodeFactory.instance.objectNode();
    document.set("urn", JsonNodeFactory.instance.textNode(urn.toString()));
    document.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document.set("subtypes", JsonNodeFactory.instance.textNode("view"));
    document.set("platform", JsonNodeFactory.instance.textNode("hive"));
    document.set("removed", JsonNodeFactory.instance.booleanNode(true));
    elasticSearchService.upsertDocument(ENTITY_NAME, document.toString(), urn.toString());

    Urn urn2 = new TestEntityUrn("test", "testUrn", "VALUE_2");
    ObjectNode document2 = JsonNodeFactory.instance.objectNode();
    document2.set("urn", JsonNodeFactory.instance.textNode(urn2.toString()));
    document2.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document2.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document2.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document2.set("subtypes", JsonNodeFactory.instance.textNode("table"));
    document2.set("platform", JsonNodeFactory.instance.textNode("hive"));
    document.set("removed", JsonNodeFactory.instance.booleanNode(false));
    elasticSearchService.upsertDocument(ENTITY_NAME, document2.toString(), urn2.toString());

    Urn urn3 = new TestEntityUrn("test", "testUrn", "VALUE_3");
    ObjectNode document3 = JsonNodeFactory.instance.objectNode();
    document3.set("urn", JsonNodeFactory.instance.textNode(urn3.toString()));
    document3.set("keyPart1", JsonNodeFactory.instance.textNode("test"));
    document3.set("textFieldOverride", JsonNodeFactory.instance.textNode("textFieldOverride"));
    document3.set("browsePaths", JsonNodeFactory.instance.textNode("/a/b/c"));
    document3.set("subtypes", JsonNodeFactory.instance.textNode("table"));
    document3.set("platform", JsonNodeFactory.instance.textNode("snowflake"));
    document.set("removed", JsonNodeFactory.instance.booleanNode(false));
    elasticSearchService.upsertDocument(ENTITY_NAME, document3.toString(), urn3.toString());

    syncAfterWrite(getBulkProcessor());

    searchResult =
        searchService.searchAcrossEntities(
            ImmutableList.of(),
            "test",
            filterWithCondition,
            null,
            0,
            10,
            new SearchFlags().setFulltext(true));
    assertEquals(searchResult.getNumEntities().intValue(), 1);
    assertEquals(searchResult.getEntities().get(0).getEntity(), urn3);
    clearCache();
  }
}
