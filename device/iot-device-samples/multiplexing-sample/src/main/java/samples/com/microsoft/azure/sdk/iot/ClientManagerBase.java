package samples.com.microsoft.azure.sdk.iot;

import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeCallback;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeReason;
import com.microsoft.azure.sdk.iot.device.exceptions.DeviceOperationTimeoutException;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

import static com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeReason.RETRY_EXPIRED;
import static com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus.DISCONNECTED;

/**
 * This is the base class for DeviceClientManager and MultiplexingClientManager
 * The shared logic for reconnection and handling status change callback exists here.
 */
@Slf4j
public abstract class ClientManagerBase implements IotHubConnectionStatusChangeCallback, ConnectionStatusTracker
{
    protected static final int SLEEP_TIME_BEFORE_RECONNECTING_IN_SECONDS = 2;

    // Initialize the connection status as DISCONNECTED
    protected ConnectionStatus lastKnownConnectionStatus = ConnectionStatus.DISCONNECTED;

    // Tracks connection status of the dependency connection (multiplexing client connection status for example in this case for DeviceClientManager)
    protected ConnectionStatusTracker dependencyConnectionStatusTracker;

    public abstract void openClient() throws IOException;
    public abstract void closeClient() throws IOException;
    public abstract String getClientId();

    // Since the client manager is in charge of handling the connection status callback, this method is a no-op
    public void registerConnectionStatusChangeCallback(IotHubConnectionStatusChangeCallback callback, Object callbackContext)
    {
        return;
    }

    public void closeNow()
    {
        synchronized (lastKnownConnectionStatus)
        {
            try
            {
                log.debug("Closing the client instance " + getClientId() + " ...");
                closeClient();
            }
            catch (IOException ex)
            {
                log.error("Exception thrown while closing client instance " + getClientId() + " : ", ex);
            }
            finally
            {
                // Once the connection is closed, set the Status to DISCONNECTED
                lastKnownConnectionStatus = ConnectionStatus.DISCONNECTED;
            }
        }
    }

    // When client manager is being opened it first makes sure the client is in a DISCONNECTED state
    // If the client is in CONNECTING or CONNECTED state, Open will be no-op.
    public void open() throws IOException
    {
        synchronized (lastKnownConnectionStatus)
        {
            // Do not attempt to CONNECT if the connection status is not DISCONNECTED. This ensure that only one process is going to attempt to connect
            if (lastKnownConnectionStatus != ConnectionStatus.DISCONNECTED)
            {
                return;
            }
            else
            {
                // Set the connection status to CONNECTING
                lastKnownConnectionStatus = ConnectionStatus.CONNECTING;
            }
        }

        establishConnection();
    }

    @Override
    public ConnectionStatus getConnectionStatus(){
        return this.lastKnownConnectionStatus;
    }

    @Override
    public void execute(IotHubConnectionStatus status, IotHubConnectionStatusChangeReason statusChangeReason, Throwable throwable, Object callbackContext)
    {
        if (throwable == null)
        {
            log.info("CONNECTION STATUS UPDATE FOR CLIENT: " + getClientId() + " - " + status + ", " + statusChangeReason);
        }
        else
        {
            log.info("CONNECTION STATUS UPDATE FOR CLIENT: " + getClientId() + " - " + status + ", " + statusChangeReason + ", " + throwable.getMessage());
            throwable.printStackTrace();
        }

        if (shouldDeviceReconnect(status, statusChangeReason, throwable))
        {
            handleRecoverableDisconnection();
        }
    }

    // This detects the state where the DeviceClient reports that OperationTimeout has expired, and stops retrying; even though the applied RetryPolicy is still valid.
    // The logic to identify whether or not the connection should be established lives in this method.
    // The client will automatically retry to establish the connection if the error is retryable
    protected boolean shouldDeviceReconnect(IotHubConnectionStatus status, IotHubConnectionStatusChangeReason statusChangeReason, Throwable throwable)
    {
        return (status == DISCONNECTED && statusChangeReason == RETRY_EXPIRED && throwable instanceof DeviceOperationTimeoutException);
    }

    // We only expect the connection status to be CONNECTED by the time we enter this state.
    public void handleRecoverableDisconnection() {
        // If the lastKnownConnectionStatus is not in a CONNECTED state it can mean two things:
        // 1: the status is CONNECTING, in which case there is nothing to be done at this time.
        // 2: the status is DISCONNECTED, in which case connection cannot be re-established.
        if (lastKnownConnectionStatus == ConnectionStatus.CONNECTED)
        {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    log.debug("Attempting reconnect for client: " + getClientId() + " ...");
                    synchronized (lastKnownConnectionStatus)
                    {
                        if (lastKnownConnectionStatus == ConnectionStatus.CONNECTED)
                        {
                            try
                            {
                                closeClient();
                            }
                            catch (Exception ex)
                            {
                                log.warn("Client " + getClientId() + " closeNow failed.", ex);
                            }
                            finally
                            {
                                lastKnownConnectionStatus = ConnectionStatus.CONNECTING;
                            }
                        }
                        else
                        {
                            log.debug("Client `" + getClientId() + "` is currently connecting; skipping...");
                            return;
                        }
                    }

                    // The client is now closed and the connection status is CONNECTING. Connection can be established now.
                    try
                    {
                        establishConnection();
                    }
                    catch (IOException ex)
                    {
                        log.error("Exception thrown while opening client instance: " + getClientId(), ex);
                    }
                }
            }).start();
        }
    }

    public void establishConnection() throws IOException
    {
        // Device client does not have retry on the initial open() call. Will need to be re-opened by the calling application
        // Lock the lastKnownConnectionStus so no other process will be able to change it while the client manager is attempting to open the connection.
        synchronized (lastKnownConnectionStatus)
        {
            while (lastKnownConnectionStatus == ConnectionStatus.CONNECTING)
            {
                // If the client has dependencies to another client (in this case it could be the multiplexing client) we have to wait to make sure the
                // dependent connection is established first.
                if (dependencyConnectionStatusTracker != null && dependencyConnectionStatusTracker.getConnectionStatus() == ConnectionStatus.CONNECTING)
                {
                    try
                    {
                        log.info("Waiting for the dependent connection to be established before attempting to open the connection");
                        Thread.sleep(SLEEP_TIME_BEFORE_RECONNECTING_IN_SECONDS * 1000);
                        continue;
                    }
                    catch (InterruptedException ex)
                    {
                        throw new RuntimeException("Interrupted while waiting between attempting to open the client: ", ex);
                    }
                }

                try
                {
                    log.debug("Opening the client instance " + getClientId() + " ...");
                    openClient();
                    lastKnownConnectionStatus = ConnectionStatus.CONNECTED;
                    break;
                }
                catch (Exception ex)
                {
                    if (ex.getCause() instanceof TransportException && ((TransportException) ex.getCause()).isRetryable())
                    {
                        log.warn("Transport exception thrown while opening client instance " + getClientId() + ", retrying: ", ex);
                    }
                    else
                    {
                        log.error("Non-retryable exception thrown while opening client instance " + getClientId() + ": ", ex);
                        lastKnownConnectionStatus = ConnectionStatus.DISCONNECTED;
                        throw ex;
                    }
                }

                try
                {
                    log.debug("Sleeping for " + SLEEP_TIME_BEFORE_RECONNECTING_IN_SECONDS + " secs before attempting another open()");
                    Thread.sleep(SLEEP_TIME_BEFORE_RECONNECTING_IN_SECONDS * 1000);
                }
                catch (InterruptedException ex)
                {
                    throw new RuntimeException("Interrupted while waiting between attempting to open the client: ", ex);
                }
            }
        }
    }
}