package matt.dad.mumtoson;

public interface NetworkTaskCallbacks
{
    public void onReceiveInput( String data );


    public void onConnect();


    public void onDisconnect();
}
