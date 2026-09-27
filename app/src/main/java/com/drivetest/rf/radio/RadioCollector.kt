package com.drivetest.rf.radio

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.drivetest.rf.core.GsmCell
import com.drivetest.rf.core.LteCell
import com.drivetest.rf.core.Neighbor
import com.drivetest.rf.core.NrCell
import com.drivetest.rf.core.RadioSnapshot
import com.drivetest.rf.core.WcdmaCell
import java.util.concurrent.Executors

/**
 * Reads serving + neighbour cells from the modem via the public TelephonyManager API.
 * Requires ACCESS_FINE_LOCATION and READ_PHONE_STATE, and Location turned on.
 */
@SuppressLint("MissingPermission")
class RadioCollector(context: Context) {
    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var cells: List<CellInfo> = emptyList()
    @Volatile private var strength: SignalStrength? = null
    @Volatile private var display: TelephonyDisplayInfo? = null

    private val callback = object : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.CellInfoListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) { strength = signalStrength }
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) { display = telephonyDisplayInfo }
        override fun onCellInfoChanged(cellInfo: List<CellInfo>) { cells = cellInfo }
    }

    fun start() {
        tm.registerTelephonyCallback(executor, callback)
        cells = tm.allCellInfo.orEmpty()
        strength = tm.signalStrength
    }

    fun stop() {
        runCatching { tm.unregisterTelephonyCallback(callback) }
        executor.shutdown()
    }

    /** Asks the modem for fresh measurements; the result lands asynchronously in [cells]. */
    fun requestRefresh() {
        runCatching {
            tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: List<CellInfo>) { cells = cellInfo }
            })
        }
    }

    fun snapshot(): RadioSnapshot {
        val list = cells
        val dataType = runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val override = display?.overrideNetworkType ?: TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
        // 3 = NR_NSA, 4 = NR_NSA_MMWAVE (deprecated), 5 = NR_ADVANCED
        val nsa = override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            override == 4 || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED

        val neighbors = mutableListOf<Neighbor>()

        // NR: registered cell = SA serving cell; in NSA the NR leg is usually not "registered",
        // so take the strongest reported NR cell as the secondary cell group.
        val nrCells = list.filterIsInstance<CellInfoNr>().map { it.isRegistered to nrCell(it) }
        val nrServing = nrCells.firstOrNull { it.first }?.second
            ?: if (nsa) nrCells.maxByOrNull { it.second.ssRsrp ?: Int.MIN_VALUE }?.second else null
        nrCells.map { it.second }.filter { it !== nrServing }
            .forEach { neighbors += Neighbor("NR", it.arfcn, it.pci, it.ssRsrp, it.ssSinr) }

        var lte: LteCell? = null
        var wcdma: WcdmaCell? = null
        var gsm: GsmCell? = null
        for (ci in list) {
            when (ci) {
                is CellInfoLte -> {
                    val c = lteCell(ci)
                    if (ci.isRegistered && lte == null) lte = c
                    else neighbors += Neighbor("LTE", c.earfcn, c.pci, c.rsrp, c.rsrq)
                }
                is CellInfoWcdma -> {
                    val c = wcdmaCell(ci)
                    if (ci.isRegistered && wcdma == null) wcdma = c
                    else neighbors += Neighbor("WCDMA", c.uarfcn, c.psc, c.rscp, c.ecno)
                }
                is CellInfoGsm -> {
                    val c = gsmCell(ci)
                    if (ci.isRegistered && gsm == null) gsm = c
                    else neighbors += Neighbor("GSM", c.arfcn, c.bsic, c.rssi, null)
                }
                else -> Unit
            }
        }

        // Many NSA devices omit NR (and sometimes LTE) from getAllCellInfo but still report the
        // signal strength, so fall back to SignalStrength for the values without cell identity.
        val ss = strength
        val nr = nrServing ?: ss?.getCellSignalStrengths(CellSignalStrengthNr::class.java)
            ?.firstOrNull { it.ssRsrp.v() != null }
            ?.let { s ->
                NrCell(false, null, null, null, null, null,
                    s.ssRsrp.v(), s.ssRsrq.v(), s.ssSinr.v(), s.csiRsrp.v(), s.csiRsrq.v(), s.csiSinr.v())
            }
        if (lte == null) {
            lte = ss?.getCellSignalStrengths(CellSignalStrengthLte::class.java)
                ?.firstOrNull { it.rsrp.v() != null }
                ?.let { s ->
                    LteCell(false, null, null, null, null, null, null,
                        s.rsrp.v(), s.rsrq.v(), s.rssnr.v(), s.rssi.v(), s.cqi.v(), s.timingAdvance.v())
                }
        }

        val tech = when {
            dataType == TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            nsa -> "5G NSA"
            dataType == TelephonyManager.NETWORK_TYPE_LTE || dataType == 19 ->
                if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
                    override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO) "LTE-A" else "LTE"
            dataType in THREE_G -> "3G"
            dataType in TWO_G -> "2G"
            nrServing?.registered == true -> "5G SA"
            lte?.registered == true -> "LTE"
            wcdma != null -> "3G"
            gsm != null -> "2G"
            else -> "No service"
        }

        return RadioSnapshot(
            time = System.currentTimeMillis(),
            tech = tech,
            dataNetwork = networkName(dataType),
            overrideNetwork = overrideName(override),
            operator = tm.networkOperatorName.orEmpty(),
            mccMnc = tm.networkOperator.orEmpty(),
            nr = nr, lte = lte, wcdma = wcdma, gsm = gsm,
            neighbors = neighbors,
        )
    }

    private fun nrCell(ci: CellInfoNr): NrCell {
        val id = ci.cellIdentity as CellIdentityNr
        val s = ci.cellSignalStrength as CellSignalStrengthNr
        return NrCell(
            registered = ci.isRegistered,
            nci = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG },
            pci = id.pci.v(), tac = id.tac.v(), arfcn = id.nrarfcn.v(), bands = id.bands.bandString("n"),
            ssRsrp = s.ssRsrp.v(), ssRsrq = s.ssRsrq.v(), ssSinr = s.ssSinr.v(),
            csiRsrp = s.csiRsrp.v(), csiRsrq = s.csiRsrq.v(), csiSinr = s.csiSinr.v(),
        )
    }

    private fun lteCell(ci: CellInfoLte): LteCell {
        val id: CellIdentityLte = ci.cellIdentity
        val s = ci.cellSignalStrength
        return LteCell(
            registered = ci.isRegistered,
            eci = id.ci.v(), pci = id.pci.v(), tac = id.tac.v(), earfcn = id.earfcn.v(),
            bands = id.bands.bandString("B"), bandwidthKhz = id.bandwidth.v(),
            rsrp = s.rsrp.v(), rsrq = s.rsrq.v(), sinr = s.rssnr.v(), rssi = s.rssi.v(),
            cqi = s.cqi.v(), ta = s.timingAdvance.v(),
        )
    }

    private fun wcdmaCell(ci: CellInfoWcdma): WcdmaCell {
        val id: CellIdentityWcdma = ci.cellIdentity
        val s = ci.cellSignalStrength
        return WcdmaCell(ci.isRegistered, id.cid.v(), id.lac.v(), id.uarfcn.v(), id.psc.v(), s.dbm.v(), s.ecNo.v())
    }

    private fun gsmCell(ci: CellInfoGsm): GsmCell {
        val id: CellIdentityGsm = ci.cellIdentity
        val s = ci.cellSignalStrength
        return GsmCell(ci.isRegistered, id.cid.v(), id.lac.v(), id.arfcn.v(), id.bsic.v(), s.rssi.v(), s.bitErrorRate.v())
    }

    private fun Int.v(): Int? = if (this == CellInfo.UNAVAILABLE) null else this
    private fun IntArray.bandString(prefix: String): String? =
        if (isEmpty()) null else joinToString("/") { "$prefix$it" }

    companion object {
        private val THREE_G = setOf(
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        )
        private val TWO_G = setOf(
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GSM,
        )

        fun networkName(t: Int) = when (t) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            19 -> "LTE-CA"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            else -> "UNKNOWN"
        }

        fun overrideName(o: Int) = when (o) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADV_PRO"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
            4 -> "NR_NSA_MMWAVE"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED"
            else -> o.toString()
        }
    }
}
