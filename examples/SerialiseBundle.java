FileOutputStream fos = context.openFileOutput(localFilename, Context.MODE_PRIVATE);
Parcel p = Parcel.obtain(); // i make an empty one here, but you can use yours
fos.write(p.marshall());
fos.flush();
fos.close();

if(savedInstanceState != null)
{
    loadGameDataFromSavedInstanceState(savedInstanceState);
}
else
{
    loadGameDataFromSharedPreferences(getPreferences(MODE_PRIVATE));
}

onPause()
{
    // get a SharedPreferences editor for storing game data to
    SharedPreferences.Editor mySharedPreferences = getPreferences(MODE_PRIVATE).edit();

    // call a function to actually store the game data
    saveGameDataToSharedPreferences(mySharedPreferences);

   // make sure you call mySharedPreferences.commit() at the end of your function
}

onResume()
{
    loadGameDataFromSharedPreferences(getPreferences(MODE_PRIVATE));
}


private void saveToPreferences(Bundle in) {
    Parcel parcel = Parcel.obtain();
    String serialized = null;
    try {
        in.writeToParcel(parcel, 0);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        IOUtils.write(parcel.marshall(), bos);

        serialized = Base64.encodeToString(bos.toByteArray(), 0);
    } catch (IOException e) {
        Log.e(getClass().getSimpleName(), e.toString(), e);
    } finally {
        parcel.recycle();
    }
    if (serialized != null) {
        SharedPreferences settings = getSharedPreferences(PREFS, 0);
        Editor editor = settings.edit();
        editor.putString("parcel", serialized);
        editor.commit();
    }
}

private Bundle restoreFromPreferences() {
    Bundle bundle = null;
    SharedPreferences settings = getSharedPreferences(PREFS, 0);
    String serialized = settings.getString("parcel", null);

    if (serialized != null) {
        Parcel parcel = Parcel.obtain();
        try {
            byte[] data = Base64.decode(serialized, 0);
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            bundle = parcel.readBundle();
        } finally {
            parcel.recycle();
        }
    }
    return bundle;
}



private static final Gson sGson = new GsonBuilder().create();
private static final String CHARSET = "UTF-8";
// taken from http://www.javacamp.org/javaI/primitiveTypes.html
private static final int BOOLEAN_LEN = 1;
private static final int INTEGER_LEN = 4;
private static final int DOUBLE_LEN = 8;

 public static byte[] serializeBundle(Bundle bundle) {
    try {
        List<SerializedItem> list = new ArrayList<>();
        if (bundle != null) {
            Set<String> keys = bundle.keySet();
            for (String key : keys) {
                Object value = bundle.get(key);
                if (value == null) continue;
                SerializedItem bis = new SerializedItem();
                bis.setClassName(value.getClass().getCanonicalName());
                bis.setKey(key);
                if (value instanceof String)
                    bis.setValue(((String) value).getBytes(CHARSET));
                else if (value instanceof SpannableString) {
                    String str = Html.toHtml((Spanned) value);
                    bis.setValue(str.getBytes(CHARSET));
                } else if (value.getClass().isAssignableFrom(Integer.class)) {
                    ByteBuffer b = ByteBuffer.allocate(INTEGER_LEN);
                    b.putInt((Integer) value);
                    bis.setValue(b.array());
                } else if (value.getClass().isAssignableFrom(Double.class)) {
                    ByteBuffer b = ByteBuffer.allocate(DOUBLE_LEN);
                    b.putDouble((Double) value);
                    bis.setValue(b.array());
                } else if (value.getClass().isAssignableFrom(Boolean.class)) {
                    ByteBuffer b = ByteBuffer.allocate(INTEGER_LEN);
                    boolean v = (boolean) value;
                    b.putInt(v ? 1 : 0);
                    bis.setValue(b.array());
                } else
                    continue; // we do nothing in this case since there is amazing amount of stuff you can put into bundle but if you want something specific you can still add it
//                        throw new IllegalStateException("Unable to serialize class + " + value.getClass().getCanonicalName());

                list.add(bis);
            }
            return sGson.toJson(list).getBytes(CHARSET);
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
    throw new IllegalStateException("Unable to serialize " + bundle);
}

public static Bundle deserializeBundle(byte[] toDeserialize) {
    try {
        Bundle bundle = new Bundle();
        if (toDeserialize != null) {
            SerializedItem[] bundleItems = new Gson().fromJson(new String(toDeserialize, CHARSET), SerializedItem[].class);
            for (SerializedItem bis : bundleItems) {
                if (String.class.getCanonicalName().equals(bis.getClassName()))
                    bundle.putString(bis.getKey(), new String(bis.getValue()));
                else if (Integer.class.getCanonicalName().equals(bis.getClassName()))
                    bundle.putInt(bis.getKey(), ByteBuffer.wrap(bis.getValue()).getInt());
                else if (Double.class.getCanonicalName().equals(bis.getClassName()))
                    bundle.putDouble(bis.getKey(), ByteBuffer.wrap(bis.getValue()).getDouble());
                else if (Boolean.class.getCanonicalName().equals(bis.getClassName())) {
                    int v = ByteBuffer.wrap(bis.getValue()).getInt();
                    bundle.putBoolean(bis.getKey(), v == 1);
                } else
                    throw new IllegalStateException("Unable to deserialize class " + bis.getClassName());
            }
        }
        return bundle;
    } catch (Exception e) {
        e.printStackTrace();
    }
    throw new IllegalStateException("Unable to deserialize " + Arrays.toString(toDeserialize));
}


@DatabaseField(dataType = DataType.BYTE_ARRAY)
private byte[] mRawBundle;

public class SerializedItem {


private String mClassName;
private String mKey;
private byte[] mValue;

// + getters and setters 
}