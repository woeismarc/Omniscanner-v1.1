package com.marc.testscanner

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

data class MacVendorResponse(
    @SerializedName("vendorName")
    val vendorName: String?,
    @SerializedName("macPrefix")
    val macPrefix: String?,
    @SerializedName("isPrivate")
    val isPrivate: Boolean = false
)

interface MacVendorService {
    @GET("search/{mac}")
    suspend fun getMacVendor(@Path("mac") mac: String): List<MacVendorResponse>?
}

class MacResolver(private val context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("mac_cache", Context.MODE_PRIVATE)
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.macvendors.com/")
        .client(OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    private val macService = retrofit.create(MacVendorService::class.java)
    private val gson = Gson()
    
    // Built-in OUI database for common manufacturers (most updated)
    private val hardcodedMac = mapOf(
        // Apple
        "000000" to "Apple",
        "0005CD" to "Apple",
        "0017F2" to "Apple",
        "002312" to "Apple",
        "002500" to "Apple",
        "0026BB" to "Apple",
        "28CFDA" to "Apple",
        "54E43A" to "Apple",
        "84253F" to "Apple",
        "ACAFB9" to "Apple",
        "DC215C" to "Apple",
        "E4E749" to "Apple",
        "00A40B" to "Apple",
        "00E05C" to "Apple",
        "0819F6" to "Apple",
        "1C1A8A" to "Apple",
        "3C0754" to "Apple",
        "4C36BC" to "Apple",
        "6C40B0" to "Apple",
        "A09066" to "Apple",
        "C4B301" to "Apple",
        "D06958" to "Apple",
        "E09906" to "Apple",
        "F4D4D4" to "Apple",
        "F4F5E8" to "Apple",
        "F8DB88" to "Apple",
        
        // Samsung
        "001C20" to "Samsung",
        "3C15C2" to "Samsung",
        "10C60F" to "Samsung",
        "28E14C" to "Samsung",
        "38201F" to "Samsung",
        "5C5AA5" to "Samsung",
        "00000C" to "Cisco",
        "404D7F" to "Google",
        "54E43A" to "Google",
        "38083F" to "Google (Nest)",
        "74EA3A" to "Google (Nest)",
        "18B430" to "Google Pixel",
        "2C6906" to "Google Pixel",
        "4483FD" to "Google Pixel",
        "9C4FDD" to "Google Pixel",
        
        // Microsoft
        "0017FA" to "Microsoft",
        "00B0D0" to "Microsoft",
        "00AA00" to "Microsoft",
        "5017D0" to "Microsoft",
        "6CC216" to "Microsoft Surface",
        
        // Amazon
        "007061" to "Amazon",
        "68EE1B" to "Amazon (FireTV/Alexa)",
        "F83B30" to "Amazon (Alexa)",
        
        // Intel
        "001000" to "Intel",
        "00AA00" to "Intel",
        "001A8C" to "Intel",
        "001EEC" to "Intel",
        "00248C" to "Intel",
        
        // NVIDIA
        "00040E" to "NVIDIA",
        
        // LG
        "00047E" to "LG",
        "001DBA" to "LG",
        "5CBA37" to "LG",
        
        // Sony
        "0001C9" to "Sony",
        "0030F1" to "Sony",
        "686899" to "Sony",
        
        // Nintendo
        "0018B8" to "Nintendo",
        "009471" to "Nintendo",
        
        // Raspberry Pi
        "B827EB" to "Raspberry Pi",
        "DC1F45" to "Raspberry Pi",
        
        // Arduino
        "A4CF12" to "Arduino",
        "90A2DA" to "Arduino",
        
        // TP-Link
        "74EA3A" to "TP-Link",
        "000000" to "TP-Link",
        
        // Netgear
        "544C05" to "Netgear",
        "001A2F" to "Netgear",
        
        // Ubiquiti
        "00188A" to "Ubiquiti",
        
        // Linksys
        "0026F6" to "Linksys",
        
        // D-Link
        "000FFE" to "D-Link",
        
        // Asus
        "000C43" to "Asus",
        "08601B" to "Asus",
        
        // Qualcomm
        "00002A" to "Qualcomm",
        "00AA00" to "Qualcomm",
        
        // Broadcom
        "001018" to "Broadcom",
        "001EC1" to "Broadcom",
        
        // Atheros
        "00130B" to "Atheros",
        "000B05" to "Atheros",
        
        // Realtek
        "000C43" to "Realtek",
        
        // MediaTek
        "0A40C0" to "MediaTek",
        
        // Marvell
        "00009B" to "Marvell",
        
        // Other IoT Brands
        "000894" to "Philips (Hue)",
        "0013C3" to "Tesla",
        "18E84E" to "DJI",
        "74850E" to "Apple Watch",
        "1A1234" to "Generic Wearable",
        "0055DA" to "Garmin",
        "00E0B0" to "GoPro"
    )

    suspend fun resolveMac(macAddress: String): String = withContext(Dispatchers.IO) {
        try {
            val cleanMac = macAddress.uppercase().replace(":", "").take(6)
            
            // First check hardcoded database
            hardcodedMac[cleanMac]?.let { return@withContext it }
            
            // Then check cache
            val cached = getCachedManufacturer(cleanMac)
            if (cached != null) {
                return@withContext cached
            }
            
            // Finally, try online lookup
            return@withContext try {
                val response = macService.getMacVendor(cleanMac)
                val manufacturer = response?.firstOrNull()?.vendorName ?: "Unknown/Generic"
                cacheManufacturer(cleanMac, manufacturer)
                manufacturer
            } catch (e: Exception) {
                // Fallback to generic if online lookup fails
                "Unknown/Generic"
            }
        } catch (e: Exception) {
            "Unknown/Generic"
        }
    }
    
    fun resolveMacBlocking(macAddress: String): String {
        return try {
            val cleanMac = macAddress.uppercase().replace(":", "").take(6)
            
            // First check hardcoded database
            hardcodedMac[cleanMac]?.let { return it }
            
            // Then check cache
            getCachedManufacturer(cleanMac)?.let { return it }
            
            // Fallback to generic
            "Unknown/Generic"
        } catch (e: Exception) {
            "Unknown/Generic"
        }
    }
    
    private fun getCachedManufacturer(macPrefix: String): String? {
        return sharedPreferences.getString("mac_$macPrefix", null)
    }
    
    private fun cacheManufacturer(macPrefix: String, manufacturer: String) {
        sharedPreferences.edit().putString("mac_$macPrefix", manufacturer).apply()
    }
    
    fun clearCache() {
        sharedPreferences.edit().clear().apply()
    }
}
