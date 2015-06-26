package water.api;

import water.Key;
import water.MemoryManager;
import water.api.KeyV3.FrameKeyV3;
import water.api.KeyV3.VecKeyV3;
import water.fvec.*;
import water.fvec.Frame.VecSpecifier;
import water.parser.ValueString;
import water.util.*;
import water.util.DocGen.HTML;

/**
 * All the details on a Frame.  Note that inside ColV3 there are fields which won't be
 * populated if we don't compute rollups, e.g. via
 * the REST API endpoint /Frames/<frameid>/columns/<colname>/summary.
 */
public class FrameV3 extends FrameBase<Frame, FrameV3> {

  // Input fields
  @API(help="Row offset to display",direction=API.Direction.INPUT)
  public long row_offset;

  @API(help="Number of rows to display",direction=API.Direction.INOUT)
  public int row_count;

  @API(help="Column offset to return", direction=API.Direction.INOUT)
  public int column_offset;

  @API(help="Number of columns to return", direction=API.Direction.INOUT)
  public int column_count;

  @API(help="Total number of columns in the Frame", direction=API.Direction.INOUT)
  public int total_column_count;

  // Output fields
  @API(help="checksum", direction=API.Direction.OUTPUT)
  public long checksum;

  @API(help="Number of rows in the Frame", direction=API.Direction.OUTPUT)
  public long rows;

  @API(help="Default percentiles, from 0 to 1", direction=API.Direction.OUTPUT)
  public double[] default_percentiles;

  @API(help="Columns in the Frame", direction=API.Direction.OUTPUT)
  public ColV3[] columns;

  @API(help="Compatible models, if requested", direction=API.Direction.OUTPUT)
  public String[] compatible_models;

  @API(help="The set of IDs of vectors in the Frame", direction=API.Direction.OUTPUT)
  public VecKeyV3[] vec_ids;

  @API(help="Chunk summary", direction=API.Direction.OUTPUT)
  public TwoDimTableBase chunk_summary;

  @API(help="Distribution summary", direction=API.Direction.OUTPUT)
  public TwoDimTableBase distribution_summary;

  public static class ColSpecifierV3 extends Schema<VecSpecifier, ColSpecifierV3> {
    public ColSpecifierV3() { }
    public ColSpecifierV3(String column_name) {
      this.column_name = column_name;
    }

    @API(help="Name of the column", direction= API.Direction.INOUT)
    public String column_name;

    @API(help="List of fields which specify columns that must contain this column", direction= API.Direction.INOUT)
    public String[] is_member_of_frames;
  }

  public static class ColV3 extends Schema<Vec, ColV3> {

    static final boolean FORCE_SUMMARY = true;
    static final boolean NO_SUMMARY = false;

    public ColV3() {}

    @API(help="label", direction=API.Direction.OUTPUT)
    public String label;

    @API(help="missing", direction=API.Direction.OUTPUT)
    public long missing_count;

    @API(help="zeros", direction=API.Direction.OUTPUT)
    public long zero_count;

    @API(help="positive infinities", direction=API.Direction.OUTPUT)
    public long positive_infinity_count;

    @API(help="negative infinities", direction=API.Direction.OUTPUT)
    public long negative_infinity_count;

    @API(help="mins", direction=API.Direction.OUTPUT)
    public double[] mins;

    @API(help="maxs", direction=API.Direction.OUTPUT)
    public double[] maxs;

    @API(help="mean", direction=API.Direction.OUTPUT)
    public double mean;

    @API(help="sigma", direction=API.Direction.OUTPUT)
    public double sigma;

    @API(help="datatype: {enum, string, int, real, time, uuid}", direction=API.Direction.OUTPUT)
    public String type;

    @API(help="domain; not-null for enum columns only", direction=API.Direction.OUTPUT)
    public String[] domain;

    @API(help="cardinality of this column's domain; not-null for enum columns only", direction=API.Direction.OUTPUT)
    public int domain_cardinality;

    @API(help="data", direction=API.Direction.OUTPUT)
    public double[] data;

    @API(help="string data", direction=API.Direction.OUTPUT)
    public String[] string_data;

    @API(help="decimal precision, -1 for all digits", direction=API.Direction.OUTPUT)
    public byte precision;

    @API(help="Histogram bins; null if not computed", direction=API.Direction.OUTPUT)
    public long[] histogram_bins;

    @API(help="Start of histogram bin zero", direction=API.Direction.OUTPUT)
    public double histogram_base;

    @API(help="Stride per bin", direction=API.Direction.OUTPUT)
    public double histogram_stride;

    @API(help="Percentile values, matching the default percentiles", direction=API.Direction.OUTPUT)
    public double[] percentiles;

    transient Vec _vec;

    ColV3(String name, Vec vec, long off, int len) {
      this(name, vec, off, len, NO_SUMMARY);
    }

    ColV3(String name, Vec vec, long off, int len, boolean force_summary) {
      label=name;

      if (force_summary) {
        missing_count = vec.naCnt();
        zero_count = vec.length() - vec.nzCnt() - missing_count;
        positive_infinity_count = vec.pinfs();
        negative_infinity_count = vec.ninfs();
        mins = vec.mins();
        maxs = vec.maxs();
        mean = vec.mean();
        sigma = vec.sigma();

        // Histogram data is only computed on-demand.  By default here we do NOT
        // compute it, but will return any prior computed & cached histogram.
        histogram_bins = vec.lazy_bins();
        histogram_base = histogram_bins ==null ? 0 : vec.base();
        histogram_stride = histogram_bins ==null ? 0 : vec.stride();
        percentiles = histogram_bins ==null ? null : vec.pctiles();
      }

      type  = vec.isEnum() ? "enum" : vec.isUUID() ? "uuid" : vec.isString() ? "string" : (vec.isInt() ? (vec.isTime() ? "time" : "int") : "real");
      domain = vec.domain();
      if (vec.isEnum()) {
        domain_cardinality = domain.length;
      } else {
        domain_cardinality = 0;
      }

      len = (int)Math.min(len,vec.length()-off);
      if( vec.isUUID() ) {
        string_data = new String[len];
        for (int i = 0; i < len; i++)
          string_data[i] = vec.isNA(off + i) ? null : PrettyPrint.UUID(vec.at16l(off + i), vec.at16h(off + i));
        data = null;
      } else if ( vec.isString() ) {
        string_data = new String[len];
        ValueString vstr = new ValueString();
        for (int i = 0; i < len; i++)
          string_data[i] = vec.isNA(off + i) ? null : vec.atStr(vstr,off + i).toString();
        data = null;
      } else {
        data = MemoryManager.malloc8d(len);
        for( int i=0; i<len; i++ )
          data[i] = vec.at(off+i);
        string_data = null;
      }
      _vec = vec;               // Better HTML display, not in the JSON
      if (len > 0)  // len == 0 is presumed to be a header file
        precision = vec.chunkForRow(0).precision();

    }

    public void clearBinsField() {
      this.histogram_bins = null;
    }
  }

  public FrameV3() { super(); }

  /* Key-only constructor, for the times we only want to return the key. */
  FrameV3(Key frame_id) { this.frame_id = new FrameKeyV3(frame_id); }

  FrameV3(Frame fr) {
    this(fr, 1, (int)fr.numRows(), 0, 0); // NOTE: possible row len truncation
  }

  FrameV3(Frame f, long row_offset, int row_count) {
    this(f, row_offset, row_count, 0, 0);
  }

  FrameV3(Frame f, long row_offset, int row_count, int column_offset, int column_count) {
    this.fillFromImpl(f, row_offset, row_count, column_offset, column_count, ColV3.NO_SUMMARY);
  }

  @Override public FrameV3 fillFromImpl(Frame f) {
    return fillFromImpl(f, 1, (int)f.numRows(), 0, 0, ColV3.NO_SUMMARY);
  }

  public FrameV3 fillFromImpl(Frame f, long row_offset, int row_count, int column_offset, int column_count, boolean force_summary) {
    if( row_count == 0 ) row_count = 100;                                 // 100 rows by default
    if( column_count == 0 ) column_count = f.numCols() - column_offset; // full width by default

    row_count = (int)Math.min(row_count, row_offset + f.numRows());
    column_count = (int) Math.min(column_count, column_offset + f.numCols());

    this.frame_id = new FrameKeyV3(f._key);
    this.checksum = f.checksum();
    this.byte_size = f.byteSize();

    this.row_offset = row_offset;
    this.rows = f.numRows();
    this.row_count = row_count;

    this.total_column_count = f.numCols();
    this.column_offset = column_offset;
    this.column_count = column_count;

    this.columns = new ColV3[column_count];
    Key[] keys = f.keys();
    if(keys != null && keys.length > 0) {
      vec_ids = new VecKeyV3[column_count];
      for (int i = 0; i < column_count; i++)
        vec_ids[i] = new VecKeyV3(keys[column_offset + i]);
    }

    Vec[] vecs = f.vecs();
    for( int i = 0; i < column_count; i++ ) {
      try {
        columns[i] = new ColV3(f._names[column_offset + i], vecs[column_offset + i], this.row_offset, this.row_count, force_summary);
      }
      catch (Exception e) {
        Log.err("Caught exception processing FrameV2(", f._key.toString(), "): Vec: " + f._names[column_offset + i], e);
        throw e;
      }
    }
    this.is_text = f.numCols()==1 && vecs[0] instanceof ByteVec;
    this.default_percentiles = Vec.PERCENTILES;

    ChunkSummary cs = FrameUtils.chunkSummary(f);

    TwoDimTable chunk_summary_table = cs.toTwoDimTableChunkTypes();
    this.chunk_summary = (TwoDimTableBase)Schema.schema(this.getSchemaVersion(), chunk_summary_table).fillFromImpl(chunk_summary_table);

    TwoDimTable distribution_summary_table = cs.toTwoDimTableDistribution();
    distribution_summary = (TwoDimTableBase)Schema.schema(this.getSchemaVersion(), distribution_summary_table).fillFromImpl(distribution_summary_table);

    this._fr = f;

    return this;
  }



  public void clearBinsField() {
    for (ColV3 col: columns)
      col.clearBinsField();
  }

  @Override public HTML writeHTML_impl( HTML ab ) {
    String[] urls = RequestServer.frameChoices(getSchemaVersion(),_fr);
    for( String url : urls )
      ab.href("hex",url,url);

    // Main data display
    // Column names
    String titles[] = new String[_fr._names.length+1];
    titles[0]="";
    System.arraycopy(_fr._names,0,titles,1,_fr._names.length);
    ab.arrayHead(titles);

    // Rollup data
    final long nrows = _fr.numRows();
    formatRow(ab,"","type" ,new ColOp() { String op(ColV3 c) { return c.type; } } );
    formatRow(ab,"","min"  ,new ColOp() { String op(ColV3 c) { return rollUpStr(c, c.missing_count ==nrows ? Double.NaN : c.mins[0]); } } );
    formatRow(ab,"","max"  ,new ColOp() { String op(ColV3 c) { return rollUpStr(c, c.missing_count ==nrows ? Double.NaN : c.maxs[0]); } } );
    formatRow(ab,"","mean" ,new ColOp() { String op(ColV3 c) { return rollUpStr(c, c.missing_count ==nrows ? Double.NaN : c.mean   ); } } );
    formatRow(ab,"","sigma",new ColOp() { String op(ColV3 c) { return rollUpStr(c, c.missing_count ==nrows ? Double.NaN : c.sigma  ); } } );

    // Optional rows: missing elements, zeros, positive & negative infinities, levels
    for( ColV3 c : columns ) if( c.missing_count > 0 )
        { formatRow(ab,"class='warning'","missing",new ColOp() { String op(ColV3 c) { return c.missing_count == 0 ?"":Long.toString(c.missing_count);}}); break; }
    for( ColV3 c : columns ) if( c.zero_count > 0 )
        { formatRow(ab,"class='warning'","zeros"  ,new ColOp() { String op(ColV3 c) { return c.zero_count == 0 ?"":Long.toString(c.zero_count);}}); break; }
    for( ColV3 c : columns ) if( c.positive_infinity_count > 0 )
        { formatRow(ab,"class='warning'","+infins",new ColOp() { String op(ColV3 c) { return c.positive_infinity_count == 0 ?"":Long.toString(c.positive_infinity_count);}}); break; }
    for( ColV3 c : columns ) if( c.negative_infinity_count > 0 )
        { formatRow(ab,"class='warning'","-infins",new ColOp() { String op(ColV3 c) { return c.negative_infinity_count == 0 ?"":Long.toString(c.negative_infinity_count);}}); break; }
    for( ColV3 c : columns ) if( c.domain!=null)
        { formatRow(ab,"class='warning'","levels" ,new ColOp() { String op(ColV3 c) { return c.domain==null?"":Long.toString(c.domain.length);}}); break; }

    // Frame data
    final int len = columns.length > 0 ? columns[0].data.length : 0;
    for( int i=0; i<len; i++ ) {
      final int row = i;
      formatRow(ab,"",Long.toString(row_offset +row+1),new ColOp() {
          String op(ColV3 c) {
            return formatCell(c.data==null?0:c.data[row],c.string_data ==null?null:c.string_data[row],c,0); }
        } );
    }
    ab.arrayTail();

    return ab.bodyTail();
  }

  private abstract static class ColOp { abstract String op(ColV3 v); }
  private String rollUpStr(ColV3 c, double d) {
    return formatCell(c.domain!=null || "uuid".equals(c.type) || "string".equals(c.type) ? Double.NaN : d,null,c,4);
  }

  private void formatRow( HTML ab, String color, String msg, ColOp vop ) {
    ab.p("<tr").p(color).p(">");
    ab.cell(msg);
    for( ColV3 c : columns )  ab.cell(vop.op(c));
    ab.p("</tr>");
  }

  private String formatCell( double d, String str, ColV3 c, int precision ) {
    if( Double.isNaN(d) ) return "-";
    if( c.domain!=null ) return c.domain[(int)d];
    if( "uuid".equals(c.type) || "string".equals(c.type)) {
      // UUID and String handling
      if( str==null ) return "-";
      return "<b style=\"font-family:monospace;\">"+str+"</b>";
    }

    long l = (long)d;
    if( (double)l == d ) return Long.toString(l);
    if( precision > 0 ) return x2(d,PrettyPrint.pow10(-precision));
    Chunk chk = c._vec.chunkForRow(row_offset);
    Class Cc = chk.getClass();
    if( Cc == C1SChunk.class ) return x2(d,((C1SChunk)chk).scale());
    if( Cc == C2SChunk.class ) return x2(d,((C2SChunk)chk).scale());
    if( Cc == C4SChunk.class ) return x2(d,((C4SChunk)chk).scale());
    return Double.toString(d);
  }

  private static String x2( double d, double scale ) {
    String s = Double.toString(d);
    // Double math roundoff error means sometimes we get very long trailing
    // strings of junk 0's with 1 digit at the end... when we *know* the data
    // has only "scale" digits.  Chop back to actual digits
    int ex = (int)Math.log10(scale);
    int x = s.indexOf('.');
    int y = x+1+(-ex);
    if( x != -1 && y < s.length() ) s = s.substring(0,x+1+(-ex));
    while( s.charAt(s.length()-1)=='0' )
      s = s.substring(0,s.length()-1);
    return s;
  }
}
