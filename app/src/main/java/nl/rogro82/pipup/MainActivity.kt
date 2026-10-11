package nl.rogro82.pipup

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import nl.rogro82.pipup.Utils.getIpAddress

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // start service in foreground
        PiPupService.start(this)
    }

    override fun onResume() {
        super.onResume()

        val textViewConnection = findViewById<TextView>(R.id.textViewConnection)
        val textViewServerAddress = findViewById<TextView>(R.id.textViewServerAddress)
        val textViewPermission = findViewById<TextView>(R.id.textViewPermission)

        when (val ipAddress = getIpAddress()) {
            is String -> {
                textViewConnection.setText(R.string.server_running)
                textViewServerAddress.apply {
                    visibility = View.VISIBLE
                    text = resources.getString(
                        R.string.server_address,
                        ipAddress,
                        PiPupService.SERVER_PORT
                    )
                }
            }
            else -> {
                textViewConnection.setText(R.string.no_network_connection)
                textViewServerAddress.visibility = View.INVISIBLE
            }
        }

        val canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        textViewPermission.apply {
            visibility = if (canDrawOverlays) View.GONE else View.VISIBLE
            text = resources.getString(R.string.overlay_permission_missing, packageName)
        }
    }
}
