package com._4point.aem.formsfeeder.server.support;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonBuilderFactory;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLStreamException;

import org.slf4j.Logger;

import com._4point.aem.formsfeeder.core.datasource.DataSource;
import com._4point.aem.formsfeeder.core.datasource.DataSourceList;
import com._4point.aem.formsfeeder.core.datasource.StandardMimeTypes;
import com._4point.aem.formsfeeder.core.datasource.serialization.XmlDataSourceListDecoder;

public class DataSourceListJsonUtils {
	static final Encoder BYTE_ENCODER = Base64.getEncoder();
	
	// We pre-create and cache the jsonFactory to speed up the creation of JsonObjects.
	private static final JsonBuilderFactory jsonFactory = Json.createBuilderFactory(Collections.emptyMap());

	/**
	 * Convert a Json Object into the DataSourceList
	 * 
	 * It follows the following rules:
	 *   * JsonObjects are turned into DataSourceLists
	 *   * JsonArrays are turned into one or more DataSource entries with the same name (the name of the array entry in the parent object) 
	 *   * JsonValues are turned into DataSource entries within a DataSourceList
	 *     
	 * @param document
	 * @param logger
	 * @return
	 */
	public static final DataSourceList asDataSourceList(JsonObject document, Logger logger) {
		return addAsDataSource(DataSourceList.builder(), document, logger).build();
	}
	
	// Add JsonObject
	private static DataSourceList.Builder addAsDataSource(DataSourceList.Builder builder, JsonObject obj, Logger logger) {
		obj.forEach((k,v)->addAsDataSource(builder, k, v, logger));
		return builder;
	}
	
	// Add JsonValue
	private static DataSourceList.Builder addAsDataSource(DataSourceList.Builder builder, String key, JsonValue value, Logger logger) {
		switch (value.getValueType()) {
		case ARRAY:
			JsonArray array = value.asJsonArray();
			array.forEach(v->addAsDataSource(builder, key, v, logger));
			break;
		case OBJECT:
			builder.add(key, addAsDataSource(DataSourceList.builder(), value.asJsonObject(), logger).build());
			break;
		case FALSE:
			builder.add(key, false);
			break;
		case NULL:
			builder.add(key, new byte[0]);	// Empty input stream.
			break;
		case NUMBER:
			JsonNumber num = (JsonNumber)value;
			if (num.isIntegral()) {
				builder.add(key, ((JsonNumber)value).bigIntegerValueExact().toString());
			} else { 
				builder.add(key, ((JsonNumber)value).bigDecimalValue().toString());
			}
			break;
		case STRING:
			builder.add(key, ((JsonString)value).getString());
			break;
		case TRUE:
			builder.add(key, true);
			break;
		default:
			throw new IllegalArgumentException("Unexpected Value Type '" + value.getValueType().toString() + "'.");
		}
		return builder;
	}
	
	/**
	 * Convert a DataSourceList to a Json object.
	 * 
	 * This should produce a relatively intuitive JSON structure based on the structure of the DataSourceList.
	 * 
	 * It follows the following rules:
	 *   * DataSourceLists get turned into JSON objects (with one exception, see below)
	 *   * Within a DataSourceList, each data source with a unique name is turned into an property of the enclosing JSON object
	 *   * Within a DataSourceList, if there are multiple data sources with the same name, they are turned into an array property with that name.
	 *   * A DataSourceList that contains only entries that have empty Strings for names, the contents will be added as an array
	 *   * A DataSourceList which has a single entry with name of an empty String will generate an error because JsonObjects should not have
	 *   *    entrys without names.
	 * 
	 *  If you want to create a JSON object with an entry that is always an array, then wrap the array entries in a DataSourceList.  Leave
	 *  the entry names as empty strings and set the containing DataSourceList name to the name of the array. 
	 * 
	 * @param dataSourceList
	 * @return FormDataMultipart
	 */
	public static JsonObject asJson(final DataSourceList dataSourceList, Logger logger) {
		JsonObjectBuilder jsonBuilder = jsonFactory.createObjectBuilder();
		addDslToJsonObject(jsonBuilder, dataSourceList.list(), logger);
		return jsonBuilder.build();
	}

	// Adds a list of DataSources into JsonObject using a JsonObjectBuilder. 
	private static void addDslToJsonObject(JsonObjectBuilder jsonObjBuilder, final List<DataSource> dataSourceList, Logger logger) {
		// Create a LinkedHashMap with all the datasources indexed by datasource name
		LinkedHashMap<String, List<DataSource>> childList = new LinkedHashMap<>(dataSourceList.size() + dataSourceList.size()/4);	// create it with some size to spare.
		dataSourceList.forEach(ds->childList.compute(ds.name(), (k,v)->addItem(ds, v)));	// populates the map, maintaining lists where there are duplicate names.
		
		// Loop through the child list, if there's only one entry call addAsJsonObject otherwise create an JsonArray and add it.
		for (List<DataSource> child : childList.values()) {
			DataSource firstDs = child.get(0);
			if (child.size() > 1) {
				JsonArrayBuilder jsonBuilder = jsonFactory.createArrayBuilder();
				JsonParent arrayParent = toJsonParent(jsonBuilder);
				child.forEach(ds->addDstoJsonParent(arrayParent, ds, logger));
				jsonObjBuilder.add(firstDs.name(), jsonBuilder);
			} else {
				if (firstDs.name().isBlank()) {
					throw new IllegalArgumentException("DataSources contained in JsonObjects cannot have a blank name.");
				}
				// Get the one and only DataSource in the list.
				JsonParent objectParent = toJsonParent(jsonObjBuilder, firstDs.name());
				addDstoJsonParent(objectParent, firstDs, logger);
			}
		}
	}

	// Called from addDslToJsonObject() to add an DataSource to a List of DataSoUrces (allocates the list if it doesn't already exist).
	private static List<DataSource> addItem(DataSource ds, List<DataSource> list) {
		if (list == null) {	 
			list = new ArrayList<>();
		}
		list.add(ds);
		return list;
	}

	// Adds a single DataSource into an JsonObject or JsonArray. 
	private static void addDstoJsonParent(JsonParent jsonParent, final DataSource dataSource, Logger logger) {
		try {
			switch(dataSource.contentType().asTypeString()) {
			case XmlDataSourceListDecoder.DSL_MIME_TYPE_STR:
				XmlDataSourceListDecoder.wrap(dataSource.inputStream())
										.decode()
										.ifPresent(dsl->addDslToJsonParent(jsonParent, dsl, logger));
				break;
			case StandardMimeTypes.TEXT_PLAIN_STR:
				Charset charset = dataSource.contentType().charset();
				jsonParent.add(new String(dataSource.inputStream().readAllBytes(), charset != null ? charset : StandardCharsets.UTF_8));
				break;
			default:
				// Treat it as byte stream and base64 encode it into a String object.
				jsonParent.add(BYTE_ENCODER.encodeToString(dataSource.inputStream().readAllBytes()));
			}
		} catch (IOException | XMLStreamException | FactoryConfigurationError e) {
			String msg = e.getMessage();
			throw new IllegalStateException("Exception while converting datasources to JSON (" + (msg == null ? "null" : msg) + ").", e);
		}
	}
	
	// Add a DataSourceList into an JsonObject or JsonArray.
	private static void addDslToJsonParent(JsonParent jsonParent, final DataSourceList dataSourceList, Logger logger) {
		if (hasOnlyBlankNames(dataSourceList)) {
			JsonArrayBuilder jsonBuilder = jsonFactory.createArrayBuilder();
			addDslToJsonArray(jsonBuilder, dataSourceList.list(), logger);
			jsonParent.add(jsonBuilder);
		} else {
			JsonObjectBuilder jsonBuilder = jsonFactory.createObjectBuilder();
			addDslToJsonObject(jsonBuilder, dataSourceList.list(), logger);
			jsonParent.add(jsonBuilder);
		}
	}

	// Check to see if the entire DataSourceList is composed DataSources with blank names.
	private static boolean hasOnlyBlankNames(final DataSourceList dataSourceList) {
		return dataSourceList.stream().filter(Predicate.not(d->d.name().isBlank())).findAny().isEmpty();
	}

	// Only called when an DataSourceList full of DataSources with no name.
	private static void addDslToJsonArray(JsonArrayBuilder jsonArrayBuilder, final List<DataSource> dataSourceList, Logger logger) {
			JsonParent arrayParent = toJsonParent(jsonArrayBuilder);
			dataSourceList.forEach(ds->addDstoJsonParent(arrayParent, ds, logger));
	}
	
	// Define an interface that we will use for both JsonObjects and JsonArrays for adding children
	private static interface JsonParent {
		void add(JsonObjectBuilder objBuilder);
		void add(JsonArrayBuilder arrayConsumer);
		void add(String str);
		// Ideally we would add other add() methods for other kinds of objects but we're not quite there yet.
	}

	// Construct a JsonParent that is backed by a JsonObject
	private static JsonParent toJsonParent(JsonObjectBuilder builder, String name) {
		return new JsonParent() {
			@Override
			public void add(JsonObjectBuilder objBuilder) {
				builder.add(name, objBuilder);
			}

			@Override
			public void add(JsonArrayBuilder arrayBuilder) {
				builder.add(name, arrayBuilder);
			}

			@Override
			public void add(String str) {
				builder.add(name, str);
			}
		};
	}

	// Construct a JsonParent that is backed by a JsonArray
	private static JsonParent toJsonParent(JsonArrayBuilder builder) {
		return new JsonParent() {
			@Override
			public void add(JsonObjectBuilder objBuilder) {
				builder.add(objBuilder);
			}
	
			@Override
			public void add(JsonArrayBuilder arrayBuilder) {
				builder.add(arrayBuilder);
			}
	
			@Override
			public void add(String str) {
				builder.add(str);
			}
		};
	}
}
