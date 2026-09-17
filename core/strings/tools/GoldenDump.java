import com.android.aapt.Resources.ResourceTable;
import com.android.aapt.Resources.Type;
import com.android.aapt.Resources.Entry;
import com.android.aapt.Resources.ConfigValue;
import com.android.aapt.Resources.Value;
import com.android.aapt.Resources.Plural;
import com.android.aapt.Resources.Item;
import com.android.aapt.Resources.StyledString;
import com.android.aapt.Resources.Reference;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

/** Independent decode: aapt2-proto generated classes + protobuf-java, not the generator's wire reader. */
public class GoldenDump {
  static String esc(String s) { return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r"); }
  static String[] ARITY = {"zero","one","two","few","many","other"};
  public static void main(String[] a) throws Exception {
    TreeMap<String, String> rows = new TreeMap<>();
    try (ZipFile z = new ZipFile(a[0])) {
      for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
        ZipEntry ze = e.nextElement();
        if (!ze.getName().endsWith(".arsc.flat")) continue;
        String n = ze.getName().replace(".arsc.flat", "");
        String loc = n.substring(0, n.indexOf('_')) + "/" + n.substring(n.indexOf('_') + 1) + ".xml";
        ByteBuffer bb = ByteBuffer.wrap(z.getInputStream(ze).readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        bb.getInt(); bb.getInt(); int count = bb.getInt();
        for (int i = 0; i < count; i++) {
          int type = bb.getInt(); long len = bb.getLong(); byte[] data = new byte[(int) len]; bb.get(data);
          bb.position(bb.position() + (int) ((4 - len % 4) % 4));
          if (type != 0) continue;
          ResourceTable t = ResourceTable.parseFrom(data);
          for (com.android.aapt.Resources.Package p : t.getPackageList()) for (Type ty : p.getTypeList()) {
            if (!ty.getName().equals("string") && !ty.getName().equals("plurals")) continue;
            for (Entry en : ty.getEntryList()) for (ConfigValue cv : en.getConfigValueList()) {
              Value v = cv.getValue();
              if (v.hasItem()) rows.put(loc + "\t" + ty.getName() + "\t" + en.getName() + "\t-", item(v.getItem()));
              else for (Plural.Entry pe : v.getCompoundValue().getPlural().getEntryList())
                rows.put(loc + "\t" + ty.getName() + "\t" + en.getName() + "\t" + ARITY[pe.getArityValue()], item(pe.getItem()));
            }
          }
        }
      }
    }
    for (Map.Entry<String, String> r : rows.entrySet()) System.out.println(r.getKey() + "\t" + r.getValue());
  }
  static String item(Item it) {
    if (it.hasStr()) return "STR\t" + esc(it.getStr().getValue()) + "\t";
    if (it.hasStyledStr()) {
      StringBuilder sp = new StringBuilder();
      for (StyledString.Span s : it.getStyledStr().getSpanList()) sp.append(s.getTag()).append(':').append(s.getFirstChar()).append('-').append(s.getLastChar()).append(';');
      return "STYLED\t" + esc(it.getStyledStr().getValue()) + "\t" + esc(sp.toString());
    }
    if (it.hasRef()) return (it.getRef().getType() == Reference.Type.ATTRIBUTE ? "ATTR" : "REF") + "\t" + esc(it.getRef().getName()) + "\t";
    return "UNSUPPORTED\t" + esc(it.getValueCase().toString()) + "\t";
  }
}
