package io.deephaven.verify.api;

import java.io.Closeable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import io.deephaven.verify.connect.ColumnDefs;
import io.deephaven.verify.producer.AvroKafkaGenerator;
import io.deephaven.verify.producer.Generator;
import io.deephaven.verify.util.Ids;
import io.deephaven.verify.util.Metrics;

/**
 * Represents the configuration of table name and columns.
 */
final public class VerifyTable implements Closeable {
	final Verify verify;
	final String tableName;
	final ColumnDefs columns = new ColumnDefs();
	private long rowCount = 0;
	private int durationSecs = -1;
	private int rowPauseMillis = -1;
	private String compression = null;
	private Generator generator = null;
	
	VerifyTable(Verify verify, String tableName) {
		this.tableName = tableName;
		this.verify = verify;
	}
	
	/**
	 * Add a column definition for the table schema
	 * @param name the name of the column
	 * @param type the type of the column ( <code>string | long | int | double | float</code> )
	 * @param valuesDef range or combination of range and string
	 * @return this instance
	 */
	public VerifyTable add(String name, String type, String valuesDef) {
		columns.add(name, type, valuesDef);
		return this;
	}
	
	/**
	 * Override the profile's row count (e.g. scale.row.count).
	 * (Note: To allow for scaling all tests, this should only be done for experimentation)
	 * @param generatedRowCount how many rows the table should have
	 * @return this instance
	 */
	public VerifyTable withRowCount(int generatedRowCount) {
		rowCount = generatedRowCount;
		return this;
	}
	
	/**
	 * Override the profile's run duration (e.g. default.completion.timeout=5 minutes)
	 * @param duration the maximum run duration
	 * @param unit the unit of time for the duration
	 * @return this instance
	 */
	public VerifyTable withRunDuration(int duration, ChronoUnit unit) {
		durationSecs = (int)Duration.of(duration, unit).toSeconds();
		return this;
	}
	
	/**
	 * Override the pause between producing records (e.g. generator.pause.per.row=0 millis).
	 * (Note: Usually, this should be left alone, since adding even 1 milli can make record
	 * generation take inordinately long.)
	 * @param duration the pause between sending records
	 * @param unit the unit of time for the duration
	 * @return this instance
	 */
	public VerifyTable withRowPause(int duration, ChronoUnit unit) {
		rowPauseMillis = (int)Duration.of(duration, unit).toMillis();
		return this;
	}
	
	/**
	 * Override the default compression codec for record generation and parquet
	 * @param codec the compression codec <code>(zstd | lz4 | gzip | none)</code>
	 * @return this instance
	 */
	public VerifyTable withCompression(String codec) {
		compression = codec;
		return this;
	}
	
	/**
	 * Direct table generation to sequence through column ranges instead of randomizing them.
	 * For example, the range [1-1000] would produce exact 1000 column values, assuming it was
	 * the largest column range.  If col1=[1-1000] and col2=[1-10], the row count would be 1000
	 * @return this instance
	 */
	public VerifyTable fixed() {
		columns.setFixed();
		return this;
	}
	
	/**
	 * Direct table generation to randomized column values according to their ranges. Maximum row
	 * count is determined by configured row count (e.g. scale.row.count)
	 * @return this instance
	 */
	public VerifyTable random() {
		columns.setRandom();
		return this;
	}
	
	/**
	 * Generate the table asynchronously through Kafka using Avro serialization
	 */
	public void generateAvro() {
		var future = generateWithAvro();
		verify.addFuture(future);
	}
	
	/*  Not implemented
	public void generateJson() {
		String bootstrapServer = verify.property("client.redpanda.addr", "localhost:9092");
		generator = new JsonKafkaGenerator(bootstrapServer, tableName, columns);
		var future = generator.produce(getRowPause(), getRowCount(), getRunDuration());
		verify.addFuture(future);
	}*/
	
	/**
	 * Generate the table synchronously to a parquet file in the engine's data directory. If a parquet
	 * file already exists in the Deephaven data directory that matches this table definition, use it 
	 * and skip generation.
	 */
	public void generateParquet() {
		String q = replaceTableAndGeneratorFields(useExistingParquetQuery);
		
		AtomicBoolean usedExistingParquet = new AtomicBoolean(false);
		verify.query(q).fetchAfter("result", table->{
			usedExistingParquet.set(table.getValue(0, "UsedExistingParquet").toString().equalsIgnoreCase("true"));
		}).execute();
		
		if(usedExistingParquet.get()) return;
		
		if(rowPauseMillis < 0) withRowPause(0, ChronoUnit.MILLIS);
				
		verify.awaitCompletion(generateWithAvro());
		
		q = replaceTableAndGeneratorFields(kafkaToParquetQuery);
		verify.query(q).execute();
	}
	
	/**
	 * Shutdown and cleanup any running generator
	 */
	public void close() {
		if(generator != null) generator.close();
	}
	
	private Future<Metrics> generateWithAvro() {
		String bootstrapServer = verify.property("client.redpanda.addr", "localhost:9092");
		String schemaRegistry = "http://" + verify.property("client.schema.registry.addr", "localhost:8081");
		generator = new AvroKafkaGenerator(bootstrapServer, schemaRegistry, tableName, columns, getCompression());
		return generator.produce(getRowPause(), getRowCount(), getRunDuration());
	}
	
	private int getRowPause() {
		if(rowPauseMillis >= 0) return rowPauseMillis;
		return (int)verify.propertyAsDuration("generator.pause.per.row", "1 millis").toMillis();
	}
	
	private long getRowCount() {
		if(rowCount > 0) return rowCount;
		
		long count = columns.isFixed()?columns.getMaxValueCount():0;
		if(count > 0) return count;
		
		return verify.propertyAsIntegral("scale.row.count", "10000");
	}
	
	private int getRunDuration() {
		if(durationSecs >= 0) return durationSecs;
		return (int)verify.propertyAsDuration("default.completion.timeout", "1 minute").toSeconds();
	}
	
	private String getCompression() {
		String codec = (compression != null)?compression:verify.property("record.compression", "NONE");
		return codec.trim().toUpperCase();
	}
	
	private String getTableDefinition() {
		return "row.count=" + getRowCount() + "\n"
			+ "compression=" + getCompression() + "\n"
			+ "isfixed=" + columns.isFixed() + "\n"
			+ columns.describe();
	}
	
	private String getTableDefinitionId() {
		return "verify." + Ids.uniqueName();
	}
	
	private String replaceTableAndGeneratorFields(String query) {
		query = generatorDefValues + query;
		
		String codec = getCompression();
		String compression = codec.equals("NONE")?"":String.format(", compression_codec_name='%s'", codec);
		
		return query.replace("${table.name}", tableName)
			.replace("${compression.codec}", compression)
			.replace("${table.columns}", columns.getQuotedColumns())
			.replace("${table.rowcount}", Long.toString(getRowCount()))
			.replace("${table.duration}", Long.toString(getRunDuration()))
			.replace("${table.definition}", getTableDefinition())
			.replace("${table.definition.id}", getTableDefinitionId());
	}
	
	static final String generatorDefValues = 
		"""
		# Define files and generator configuration
		table_parquet = '/data/${table.name}.parquet'
		table_gen_parquet = '/data/${table.definition.id}.gen.parquet'
		table_gen_def_text = '''${table.definition}'''
		table_gen_def_file = '/data/${table.definition.id}.gen.def'

		""";
	
	static final String useExistingParquetQuery = 
		"""
		# Determine if a Parquet file already exists that fits the table configuration	
		import os, glob
		from deephaven import new_table
		from deephaven.column import string_col
		
		def findMatchingGenParquet(gen_def_text):
			for path in glob.glob('/data/verify.*.*.*.gen.def'):
				with open(path) as f:
					if f.read() == gen_def_text: 
						return os.path.splitext(os.path.splitext(path)[0])[0]
			return None
		
		if os.path.exists(table_parquet):
			os.remove(table_parquet)

		usedExisting = False
		matching_gen_parquet = findMatchingGenParquet(table_gen_def_text)
		if matching_gen_parquet is not None and os.path.exists(str(matching_gen_parquet) + '.gen.parquet'):
			os.link(str(matching_gen_parquet) + '.gen.parquet', table_parquet)
			usedExisting = True
			
		result = new_table([string_col("UsedExistingParquet", [str(usedExisting)])])
		""";
	
	static final String kafkaToParquetQuery =
		"""
		# Create a Parquet file from a Kafka topic
		import jpy, os
		from deephaven import kafka_consumer as kc
		from deephaven.stream.kafka.consumer import TableType, KeyValueSpec
		from deephaven.parquet import write
		from deephaven.table import Table
		from deephaven.ugp import exclusive_lock

		${table.name} = kc.consume(
			{ 'bootstrap.servers' : '${kafka.consumer.addr}', 'schema.registry.url' : 'http://${schema.registry.addr}' },
			'${table.name}', partitions=None, offsets=kc.ALL_PARTITIONS_SEEK_TO_BEGINNING,
			key_spec=KeyValueSpec.IGNORE, value_spec=kc.avro_spec('${table.name}_record', schema_version='1'),
			table_type=TableType.append()).view(formulas=[${table.columns}])

		def wait_ticking_table_update(table: Table, row_count: int):
			with exclusive_lock():
				while table.size < row_count:
					table.j_table.awaitUpdate()

		wait_ticking_table_update(${table.name}, ${table.rowcount})
		
		if os.path.exists(table_parquet):
			os.remove(table_parquet)

		with open(table_gen_def_file, 'w') as f:
			f.write(table_gen_def_text)
		write(${table.name}, table_gen_parquet ${compression.codec})
		os.link(table_gen_parquet, table_parquet)
		
		del ${table.name}
		
		System = jpy.get_type('java.lang.System')
		System.gc()
		
		""";

}
