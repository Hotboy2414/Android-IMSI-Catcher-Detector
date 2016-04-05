package com.secupwn.aimsicd.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Vibrator;
import android.preference.PreferenceManager;

import com.secupwn.aimsicd.R;
import com.secupwn.aimsicd.data.model.BTS;
import com.secupwn.aimsicd.data.model.DefaultLocation;
import com.secupwn.aimsicd.data.model.Event;
import com.secupwn.aimsicd.data.model.Import;
import com.secupwn.aimsicd.data.model.LocationInfo;
import com.secupwn.aimsicd.data.model.Measure;
import com.secupwn.aimsicd.enums.Status;
import com.secupwn.aimsicd.service.CellTracker;
import com.secupwn.aimsicd.utils.Cell;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import io.freefair.android.util.logging.AndroidLogger;
import io.freefair.android.util.logging.Logger;
import io.realm.Realm;
import io.realm.RealmQuery;
import io.realm.RealmResults;
import lombok.Cleanup;

/**
 * This class handles all the AMISICD DataBase maintenance operations, like
 * creation, population, updates, backup, restore and various selections.
 * <p/>
 * See:
 * <p/>
 * [1] http://stackoverflow.com/questions/1122679/querying-and-working-with-cursors-in-sqlite-on-android
 * [2] http://developer.android.com/reference/android/database/sqlite/SQLiteDatabase.html#rawQuery%28java.lang.String,%20java.lang.String%5B%5D%29
 */
public final class AIMSICDDbAdapter {
    private final Logger log = AndroidLogger.forClass(AIMSICDDbAdapter.class);

    private Context mContext;
    private SharedPreferences mPreferences;
    public static String mExternalFilesDirPath;

    public AIMSICDDbAdapter(Context context) {
        mContext = context;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        mExternalFilesDirPath = mContext.getExternalFilesDir(null) + File.separator;
        //e.g. /storage/emulated/0/Android/data/com.SecUpwN.AIMSICD/
    }

    // ====================================================================
    //      Populate the DB tables  (INSERT)
    // ====================================================================

    /* ====================================================================
     *      mDb.query statements (get)        SELECT
     * ====================================================================
     *  IMPORTANT!  The SQL queries as presented here are not in the same order
     *              as in the DB tables themselves, therefore it may be hard
     *              to easily match the various items when using the Cursors
     *              as shown below.
     *
     *              Thus when used in MapFragment.java at loadEntries() and
     *              loadOpenCellIDMarkers(), the index used there is completely different
     *              than what could be expected.
     *
     *  ISSUES:     [ ] To avoid un-necessary future code obscurity, we should rearrange
     *                  all Cursor queries to reflect the actual DB table order.
     *                  Todo: This is a tedious job...
     *                  TODO: Also AVOID using this type of Query!!
     *
     * ====================================================================
     */

    /**
     * Returns Cell Information for contribution to the OpenCellID project
     * by listing all rows from the {@link Measure} realm where submitted is not true.
     */
    public RealmResults<Measure> getOCIDSubmitData(Realm realm) {

        return realm.where(Measure.class)
                .notEqualTo("submitted", true)
                .findAll();
    }

    // ====================================================================
    //      Various DB operations
    // ====================================================================

    /**
     * This is using the LAC found by API and comparing to LAC found from a
     * previous measurement in the "DBi_measure". It then compares the API LAC
     * to that of the DBi_Measure LAC. This is NOT depending on {@link Import}.
     * <p/>
     * This works for now, but we probably should consider populating "DBi_measure"
     * as soon as the API gets a new LAC. Then the detection can be done by SQL,
     * and by just comparing last 2 LAC entries for same CID.
     *
     * @return false if LAC is not OK (Cell's LAC differs from Cell's LAC previously stored value in DB)
     */
    public boolean checkLAC(Realm realm, Cell cell) {
        RealmResults<BTS> btsRealmResults = realm.where(BTS.class)
                .equalTo("cellId", cell.getCid())
                .findAll();

        for (BTS bts : btsRealmResults) {
            int lac = bts.getLocationAreaCode();

            if (cell.getLocationAreaCode() != lac) {
                log.info("ALERT: Changing LAC on CID: " + cell.getCid()
                        + " LAC(API): " + cell.getLocationAreaCode()
                        + " LAC(DBi): " + lac);

                return false;
            } else {
                log.verbose("LAC checked - no change on CID:" + cell.getCid()
                        + " LAC(API): " + cell.getLocationAreaCode()
                        + " LAC(DBi): " + lac);
            }
        }
        return true;
    }


    /**
     * UPDATE {@link Measure} realm to indicate if OpenCellID DB contribution has been made
     */
    public Realm.Transaction ocidProcessed() {

        return new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                RealmResults<Measure> all = realm.where(Measure.class).findAll();

                for (Measure measure : all) {
                    measure.setSubmitted(true);
                }
            }
        };
    }


    /**
     * This returns all {@link Import} by current sim card network rather
     * than returning other bts from different networks and slowing down map view
     */
    public RealmQuery<Import> returnOcidBtsByNetwork(Realm realm, int mcc, int mnc) {

        return realm.where(Import.class)
                .equalTo("mobileCountryCode", mcc)
                .equalTo("mobileNetworkCode", mnc);
    }

    public LocationInfo getDefaultLocation(int mcc) {
        @Cleanup Realm realm = Realm.getDefaultInstance();

        return realm.where(DefaultLocation.class)
                .equalTo("mobileCountryCode", mcc)
                .findAll()
                .first()
                .getLocationInfo();

    }

    /**
     * Remove all but the last row, unless its CID is invalid.
     * <p/>
     * This will not work if: PRAGMA foreign_key=ON, then we need to
     * delete the corresponding DBi_measure entries before / as well.
     */
    public Realm.Transaction cleanseCellTable() {
        return new Realm.Transaction() {

            @Override
            public void execute(Realm realm) {
                realm.where(BTS.class)
                        .equalTo("cellId", -1)
                        .or()
                        .equalTo("cellId", Integer.MAX_VALUE)
                        .findAll()
                        .clear();
            }
        } ;
    }

    /**
     * Prepares the CSV file used to upload new data to the OCID server.
     * <p/>
     * OCID CSV upload format:
     * <p/>
     * "cellid"        = CID (in UMTS long format)
     * "measured_at"   = time
     * "rating"        = gpsd_accu
     * "act"           = RAT (TEXT):
     * 1xRTT, CDMA, eHRPD, IS95A, IS95B, EVDO_0, EVDO_A, EVDO_B,
     * UMTS, HSPA+, HSDPA, HSUPA, HSPA, LTE, EDGE, GPRS, GSM
     */
    public boolean prepareOpenCellUploadData(Realm realm) {
        boolean result;

        File dir = new File(mExternalFilesDirPath + "OpenCellID/");
        if (!dir.exists()) {
            result = dir.mkdirs();
            if (!result) {
                return false;
            }
        }
        File file = new File(dir, "aimsicd-ocid-data.csv");

        try {
            // Get data not yet submitted:
            RealmResults<Measure> c;
            c = getOCIDSubmitData(realm);
            // Check if we have something to upload:
            if (c.size() > 0) {
                if (!file.exists()) {
                    result = file.createNewFile();
                    if (!result) {
                        return false;
                    }

                    // OCID CSV upload format and items
                    // mcc,mnc,lac,cellid,lon,lat,signal,measured_at,rating,speed,direction,act,ta,psc,tac,pci,sid,nid,bid
                    CSVWriter csvWrite = new CSVWriter(new FileWriter(file));
                    // TODO: Add "act"
                    csvWrite.writeNext("mcc,mnc,lac,cellid,lon,lat,signal,measured_at,rating");

                    int size = c.size();
                    log.debug("OCID UPLOAD: row count = " + size);

                    for (Measure measure : c) {
                        csvWrite.writeNext(
                                String.valueOf(measure.getBts().getMobileCountryCode()),
                                String.valueOf(measure.getBts().getMobileNetworkCode()),
                                String.valueOf(measure.getBts().getLocationAreaCode()),
                                String.valueOf(measure.getBts().getCellId()),
                                String.valueOf(measure.getGpsd().getLongitude()),
                                String.valueOf(measure.getGpsd().getLatitude()),
                                String.valueOf(measure.getRxSignal()),
                                String.valueOf(measure.getTime().getTime()),
                                //c.getString(c.getColumnIndex("RAT")),                     // OCID: "act" TODO
                                String.valueOf(measure.getGpsd().getAccuracy())
                        );
                    }
                    csvWrite.close();
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("prepareOpenCellUploadData(): Error creating OpenCellID Upload Data: ", e);
            return false;
        }
    }


    /**
     * Parses the downloaded CSV from OpenCellID and uses it to populate "Import" table.
     * <p/>
     * a)  We do not include "rej_cause" in backups. set to 0 as default
     * b)  Unfortunately there are 2 important missing items in the OCID CSV file:
     * - "time_first"
     * - "time_last"
     * <p/>
     * c)  In addition the OCID data often contain unexplained negative values for one or both of:
     * - "samples"
     * - "range"
     * <p/>
     * d) The difference between "Cellid" and "cid", is that "cellid" is the "Long CID",
     * consisting of RNC and a multiplier:
     * Long CID = 65536 * RNC + CID
     * See FAQ.
     * <p/>
     * ========================================================================
     * For details on available OpenCellID API DB values, see:
     * http://wiki.opencellid.org/wiki/API
     * http://wiki.opencellid.org/wiki/FAQ#Long_CellID_vs._short_Cell_ID
     * ========================================================================
     * # head -2 opencellid.csv
     * lat,lon,mcc,mnc,lac,cellid,averageSignalStrength,range,samples,changeable,radio,rnc,cid,psc,tac,pci,sid,nid,bid
     * <p/>
     * 0 lat                      TEXT
     * 1 lon                      TEXT
     * 2 mcc                      INTEGER
     * 3 mnc                      INTEGER
     * 4 lac                      INTEGER
     * 5 cellid                   INTEGER     (Long CID) = 65536 * RNC + CID
     * 6 averageSignalStrength    INTEGER     (rx_power)
     * 7 range                    INTEGER     (accu)
     * 8 samples                  INTEGER
     * 9 changeable               INTEGER     (isGPSexact)
     * 10 radio                   TEXT        (RAT)
     * 11 rnc                     INTEGER
     * 12 cid                     INTEGER     CID (Short)= "Long CID" mod 65536
     * 13 psc                     INTEGER
     * --------- vvv  See OCID API  vvv ---------
     * 14 tac                     -
     * 15 pci                     -
     * 16 sid                     -
     * 17 nid                     -
     * 18 bid                     -
     * <p/>
     * 54.63376,25.160243,246,3,20,1294,0,-1,1,1,GSM,,,,,,,,
     * ========================================================================
     */
    public boolean populateDBeImport() {
        // This was not finding the file on a Samsung S5
        // String fileName = Environment.getExternalStorageDirectory()+ "/AIMSICD/OpenCellID/opencellid.csv";
        String fileName = mContext.getExternalFilesDir(null) + File.separator + "OpenCellID/opencellid.csv";
        File file = new File(fileName);

        try {
            if (file.exists()) {

                CSVReader csvReader = new CSVReader(new FileReader(file));
                List<String[]> csvCellID = new ArrayList<>();
                String next[];

                while ((next = csvReader.readNext()) != null) {
                    csvCellID.add(next);
                }

                if (!csvCellID.isEmpty()) {
                    int lines = csvCellID.size();
                    log.info("UpdateOpenCellID: OCID CSV size (lines): " + lines);

                    @Cleanup Realm realm = Realm.getDefaultInstance();
                    int rowCounter;
                    for (rowCounter = 1; rowCounter < lines; rowCounter++) {
                        // Insert details into OpenCellID Database using:  insertDBeImport()
                        // Beware of negative values of "range" and "samples"!!
                        String lat = csvCellID.get(rowCounter)[0],          //TEXT
                                lon = csvCellID.get(rowCounter)[1],          //TEXT
                                mcc = csvCellID.get(rowCounter)[2],          //int
                                mnc = csvCellID.get(rowCounter)[3],          //int
                                lac = csvCellID.get(rowCounter)[4],          //int
                                cellid = csvCellID.get(rowCounter)[5],       //int   long CID [>65535]
                                range = csvCellID.get(rowCounter)[6],        //int
                                avg_sig = csvCellID.get(rowCounter)[7],      //int
                                samples = csvCellID.get(rowCounter)[8],      //int
                                change = csvCellID.get(rowCounter)[9],       //int
                                radio = csvCellID.get(rowCounter)[10],       //TEXT
//                                rnc = csvCellID.get(rowCounter)[11],         //int
//                                cid = csvCellID.get(rowCounter)[12],         //int   short CID [<65536]
                                psc = csvCellID.get(rowCounter)[13];         //int

                        // Some OCID data may not contain PSC so we indicate this with an out-of-range
                        // PSC value. Should be -1 but hey people already imported so we're stuck with
                        // this.
                        int iPsc = 666;
                        if (psc != null && !psc.isEmpty()) {
                            iPsc = Integer.parseInt(psc);
                        }

                        //Reverse order 1 = 0 & 0 = 1
                        // what if ichange is 4? ~ agilob
                        int ichange = Integer.parseInt(change);
                        ichange = (ichange == 0 ? 1 : 0);

                        Realm.Transaction transaction = insertDBeImport(
                                "OCID",                     // DBsource
                                radio,                      // RAT
                                Integer.parseInt(mcc),      // MCC
                                Integer.parseInt(mnc),      // MNC
                                Integer.parseInt(lac),      // LAC
                                Integer.parseInt(cellid),   // CID (cellid) ?
                                iPsc,                       // psc
                                Double.parseDouble(lat),    // gps_lat
                                Double.parseDouble(lon),    // gps_lon
                                ichange == 0,               // isGPSexact
                                Integer.parseInt(avg_sig),  // avg_signal [dBm]
                                Integer.parseInt(range),    // avg_range [m]
                                Integer.parseInt(samples),  // samples
                                new Date(),                 // time_first  (not in OCID)
                                new Date(),                 // time_last   (not in OCID)
                                null                           // TODO: rej_cause , set default 0
                        );
                        realm.executeTransaction(transaction);
                    }
                    log.debug("PopulateDBeImport(): inserted " + rowCounter + " cells.");
                }
            } else {
                log.error("Opencellid.csv file does not exist!");
            }
            return true;
        } catch (Exception e) {
            log.error("Error parsing OpenCellID data: " + e.getMessage());
            return false;
        } finally {
            try {
                Thread.sleep(1000); // wait 1 second to allow user to see progress bar.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ====================================================================
    //      Cleanup and filtering of DB tables
    // ====================================================================

    /**
     * This is the {@link Import} data consistency check wich checks each
     * imported BTS data for consistency and correctness according to general
     * 3GPP LAC/CID/RAT rules and according to the app settings:
     * <p/>
     * tf_settings         (currently hard-coded)
     * min_gps_precision   (currently hard-coded)
     * <p/>
     * So there are really two steps in this procedure:
     * a) Remove bad {@link Import Imports} from Realm
     * b) Mark unsafe {@link Import Imports} with "rej_cause" value.
     * <p/>
     * The formula for the long cell ID is as follows:
     * Long CID = 65536 * RNC + CID
     * <p/>
     * If you have the Long CID, you can get RNC and CID in the following way:
     * RNC = Long CID / 65536 (integer division)
     * CID = Long CID mod 65536 (modulo operation)
     */
    public Realm.Transaction checkDBe() {
        return new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                // We hard-code these for now, but should be in the settings eventually
//        int tf_settings = 30;         // [days] Minimum acceptable number of days since "time_first" seen.
                int min_gps_precision = 50;   // [m]    Minimum acceptable GPS accuracy in meters.

                String sqlQuery;                // SQL Query string

                //=============================================================
                //===  DELETE bad cells from BTS data
                //=============================================================


                log.debug("CheckDBe() Attempting to delete bad import data from Imports database...");

                // =========== samples ===========
                realm.where(Import.class).lessThan("samples", 1).findAll().clear();

                // =========== avg_range ===========
                // TODO: OCID data marks many good BTS with a negative range so we can't use this yet.
                // TODO: Also delete cells where the avg_range is way too large, say > 2000 meter
                /*realm.where(Import.class)
                        .not()
                        .between("avgRange", 1, 2000)
                        .findAll().clear();*/

                // =========== LAC ===========
                realm.where(Import.class).lessThan("locationAreaCode", 1).findAll().clear();

                // We should delete cells with CDMA (4) LAC not in [1,65534] but we can simplify this to:
                // Delete ANY cells with a LAC not in [1,65534]
                realm.where(Import.class).greaterThan("locationAreaCode", 65534).findAll().clear();

                // Delete cells with GSM/UMTS/LTE (1/2/3/13 ??) (or all others?) LAC not in [1,65533]
                /*realm.where(Import.class)
                        .notEqualTo("radioAccessTechnology", "CDMA")
                        .greaterThan("locationAreaCode", 65533)
                        .findAll().clear();*/

                // =========== CID ===========
                realm.where(Import.class).lessThan("cell", 1).findAll().clear();

                // We should delete cells with UMTS/LTE (3,13) CID not in [1,268435455] (0xFFF FFFF) but
                // we can simplify this to:
                // Delete ANY cells with a CID not in [1,268435455]
                realm.where(Import.class).greaterThan("cellId", 268435455).findAll().clear();

                // Delete cells with GSM/CDMA (1-3,4) CID not in [1,65534]
                realm.where(Import.class)
                        .greaterThan("cellId", 65534)
                        .beginGroup()
                            .equalTo("radioAccessTechnology", "GSM")
                            .or()
                            .equalTo("radioAccessTechnology", "CDMA")
                        .endGroup()
                        .findAll().clear();
                log.info("CheckDBe() Deleted BTS entries from Import realm with bad LAC/CID...");

                //=============================================================
                //===  UPDATE "rej_cause" in Import-Data
                //=============================================================

                // =========== isGPSexact ===========
                // Increase rej_cause, when:  the GPS position of the BTS is not exact:
                // NOTE:  In OCID: "changeable"=1 ==> isGPSexact=0
                for (Import i : realm.where(Import.class).equalTo("gpsExact", false).findAll()) {
                    i.setRejCause(i.getRejCause() + 3);
                }

                // =========== avg_range ===========
                // Increase rej_cause, when:  the average range is < a minimum GPS precision
                for (Import i : realm.where(Import.class).lessThan("avgRange", min_gps_precision).findAll()) {
                    i.setRejCause(i.getRejCause() + 3);
                }

                // =========== time_first ===========
                // Increase rej_cause, when:  the time first seen is less than a number of days.
                // TODO: We need to convert tf_settings to seconds since epoch/unix time...
                //      int tf_settings = current_time[s] - (3600 * 24 * tf_settings) ???
                //sqlQuery = "UPDATE DBe_import SET rej_cause = rej_cause + 1 WHERE time_first < " + tf_settings;
                //mDb.execSQL(sqlQuery);
            }
        };

    }


    // =======================================================================================
    //      Signal Strengths Table
    // =======================================================================================

    public int getAverageSignalStrength(Realm realm, int cellID) {
        return (int) realm.where(Measure.class)
                .equalTo("bts.cellId", cellID)
                .average("rxSignal");
    }

    //=============================================================================================
    // ********************** ALL NEW FUNCTIONS ADDED AFTER THIS LINE *****************************
    //=============================================================================================

    //====================================================================
    //      START OF INSERT FUNCTIONS
    //====================================================================

    /**
     * This method is used to insert and populate the downloaded or previously
     * backed up OCID details into the {@link Import} realm table.
     * <p/>
     * It also prevents adding multiple entries of the same cell-id, when OCID
     * downloads are repeated.
     */
    public Realm.Transaction insertDBeImport(
            final String db_src,
            final String rat,
            final int mcc,
            final int mnc,
            final int lac,
            final int cid,
            final int psc,
            final double lat,
            final double lon,
            final boolean isGpsExact,
            final int avg_range,
            final int avg_signal,
            final int samples,
            final Date time_first,
            final Date time_last,
            final Integer rej_cause
    ) {

        return new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                long count = realm.where(Import.class)
                        .equalTo("locationAreaCode", lac)
                        .equalTo("cellId", cid).count();
                if (count <= 0) {
                    Import anImport = realm.createObject(Import.class);
                    anImport.setDbSource(db_src);
                    anImport.setRadioAccessTechnology(rat);
                    anImport.setMobileCountryCode(mcc);
                    anImport.setMobileNetworkCode(mnc);
                    anImport.setLocationAreaCode(lac);
                    anImport.setCellId(cid);
                    anImport.setPrimaryScramblingCode(psc);

                    LocationInfo locationInfo = realm.createObject(LocationInfo.class);
                    locationInfo.setLatitude(lat);
                    locationInfo.setLongitude(lon);

                    anImport.setLocationInfo(locationInfo);
                    anImport.setGpsExact(isGpsExact);
                    anImport.setAvgRange(avg_range);
                    anImport.setAvgSignal(avg_signal);
                    anImport.setSamples(samples);
                    anImport.setTimeFirst(time_first);
                    anImport.setTimeLast(time_last);
                    anImport.setRejCause(rej_cause);

                }
            }
        };
    }

    /**
     * Created this because we don't need to insert all the data in this table
     * since we don't yet have items like TMSI etc.
     */
    public void insertBTS(Realm realm, Cell cell) {

        // If LAC and CID are not already in BTS realm, then add them.
        if (!cellInDbiBts(realm, cell.getLocationAreaCode(), cell.getCid())) {

            realm.beginTransaction();
            BTS bts = realm.createObject(BTS.class);

            bts.setMobileCountryCode(cell.getMcc());
            bts.setMobileNetworkCode(cell.getMnc());
            bts.setLocationAreaCode(cell.getLocationAreaCode());
            bts.setCellId(cell.getCid());
            bts.setPrimaryScramblingCode(cell.getPrimaryScramblingCode());

            bts.setTimeFirst(new Date());
            bts.setTimeLast(new Date());

            LocationInfo locationInfo = realm.createObject(LocationInfo.class);
            locationInfo.setLatitude(cell.getLat());  // TODO NO! These should be exact GPS from Import or by manual addition!
            locationInfo.setLongitude(cell.getLon());  // TODO NO! These should be exact GPS from Import or by manual addition!
            bts.setLocationInfo(locationInfo);

            realm.commitTransaction();

        } else {
            // If cell is already in the DB, update it to last time seen and
            // update its GPS coordinates, if not 0.0

            BTS bts = realm.where(BTS.class).equalTo("cellId", cell.getCid()).findFirst();

            realm.beginTransaction();

            bts.setTimeLast(new Date());

            // TODO NO! These should be exact GPS from Import or by manual addition!
            // Only update if GPS coordinates are good
            if (Double.doubleToRawLongBits(cell.getLat()) != 0
                    && Double.doubleToRawLongBits(cell.getLat()) != 0
                    && Double.doubleToRawLongBits(cell.getLon()) != 0
                    && Double.doubleToRawLongBits(cell.getLon()) != 0) {
                if(bts.getLocationInfo() == null){
                    bts.setLocationInfo(realm.createObject(LocationInfo.class));
                }
                bts.getLocationInfo().setLatitude(cell.getLat());
                bts.getLocationInfo().setLongitude(cell.getLon());
            }

            realm.commitTransaction();

            log.info("BTS updated: CID=" + cell.getCid() + " LAC=" + cell.getLocationAreaCode());
        }

        // TODO: This doesn't make sense, if it's in DBi_bts it IS part of DBi_measure!
        // Checking to see if CID (now bts_id) is already in DBi_measure, if not add it.
        if (!cellInDbiMeasure(realm, cell.getCid())) {

            realm.beginTransaction();
            Measure measure = realm.createObject(Measure.class);

            BTS bts = realm.where(BTS.class).equalTo("cellId", cell.getCid()).findFirst();

            measure.setBts(bts);
            measure.setTime(new Date());

            LocationInfo locationInfo = realm.createObject(LocationInfo.class);
            locationInfo.setLatitude(cell.getLat());
            locationInfo.setLongitude(cell.getLon());
            locationInfo.setAccuracy(cell.getAccuracy());
            measure.setGpsd(locationInfo);

            measure.setRxSignal(cell.getDbm());
            measure.setRadioAccessTechnology(String.valueOf(cell.getRat()));
            measure.setTa(cell.getTimingAdvance()); //TODO does this actually get timing advance?
            measure.setSubmitted(false);
            measure.setNeighbour(false);

            realm.commitTransaction();
            log.info("Measure inserted cellId=" + cell.getCid());

        } else {
            // Updating DBi_measure tables if already exists.

            realm.beginTransaction();
            RealmResults<Measure> all = realm.where(Measure.class)
                    .equalTo("bts.cellId", cell.getCid())
                    .findAll();

            for (Measure measure : all) {

                if (Double.doubleToRawLongBits(cell.getLat()) != 0
                        && Double.doubleToRawLongBits(cell.getLon()) != 0) {
                    measure.getGpsd().setLatitude(cell.getLat());
                    measure.getGpsd().setLongitude(cell.getLon());
                }
                if (Double.doubleToRawLongBits(cell.getAccuracy()) != 0
                        && cell.getAccuracy() > 0) {
                    measure.getGpsd().setAccuracy(cell.getAccuracy());
                }

                if (cell.getDbm() > 0) {
                    measure.setRxSignal(cell.getDbm());
                }

                if (cell.getTimingAdvance() > 0) {
                    measure.setTa(cell.getTimingAdvance()); // Only available on API >16 on LTE
                }
            }
            realm.commitTransaction();
            log.info("DBi_measure updated bts_id=" + cell.getCid());

        }

    }

    /**
     * Defining a new simpler version of insertEventLog for use in CellTracker.
     * Please note, that in AMSICDDbAdapter (here) it is also used to backup DB,
     * in which case we can not use this simpler version!
     */
    public void toEventLog(Realm realm, final int DF_id, final String DF_desc) {

        final Date timestamp = new Date();
        final int lac = CellTracker.monitorCell.getLocationAreaCode();
        final int cid = CellTracker.monitorCell.getCid();
        final int psc = CellTracker.monitorCell.getPrimaryScramblingCode(); //[UMTS,LTE]
        final double gpsd_lat = CellTracker.monitorCell.getLat();
        final double gpsd_lon = CellTracker.monitorCell.getLon();
        final double gpsd_accu = CellTracker.monitorCell.getAccuracy();

        realm.executeTransactionAsync(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {

                // skip CID/LAC of "-1" (due to crappy API, Roaming or Air-Plane Mode)
                if (cid != -1 || lac != -1) {
                    // Check if LAST entry is the same!
                    RealmResults<Event> events = realm.where(Event.class).findAllSorted("timestamp");

                    boolean insertData;
                    if (events.isEmpty()) {
                        insertData = true;
                    } else {
                        Event lastEvent = events.last();
                        insertData = !(lastEvent.getCellId() == cid && lastEvent.getLocationAreaCode() == lac && lastEvent.getPrimaryScramblingCode() == psc && lastEvent.getDfId() == DF_id);
                    }
                    // WARNING: By skipping duplicate events, we might be missing counts of Type-0 SMS etc.

                    if (insertData) {

                        Event event = realm.createObject(Event.class);

                        event.setTimestamp(timestamp);
                        event.setLocationAreaCode(lac);
                        event.setCellId(cid);
                        event.setPrimaryScramblingCode(psc);

                        LocationInfo locationInfo = realm.createObject(LocationInfo.class);
                        locationInfo.setLatitude(gpsd_lat);
                        locationInfo.setLongitude(gpsd_lon);
                        locationInfo.setAccuracy(gpsd_accu);
                        event.setLocationInfo(locationInfo);

                        event.setDfId(DF_id);
                        event.setDfDescription(DF_desc);
                    }
                }
            }
        }, new Realm.Transaction.OnSuccess() {
            @Override
            public void onSuccess() {
                log.info("ToEventLog(): Added new event: id=" + DF_id + " time=" + timestamp + " cid=" + cid);

                // Short 100 ms Vibration
                // TODO not elegant solution, vibrator invocation should be moved somewhere else imho
                boolean vibrationEnabled = mPreferences.getBoolean(mContext.getString(R.string.pref_notification_vibrate_enable), true);
                int thresholdLevel = Integer.valueOf(mPreferences.getString(mContext.getString(R.string.pref_notification_vibrate_min_level), String.valueOf(Status.MEDIUM.ordinal())));
                boolean higherLevelThanThreshold = Status.MEDIUM.ordinal() <= thresholdLevel;

                if (vibrationEnabled && higherLevelThanThreshold) {
                    Vibrator v = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
                    v.vibrate(100);
                }

                // Short sound:
                // TODO see issue #15
            }
        });
    }

    /**
     * This checks if a cell with a given CID already exists in the {@link Import} realm.
     */
    public boolean openCellExists(Realm realm, int cellID) {
        return realm.where(Import.class).equalTo("cellId", cellID).count() > 0;
    }

    /**
     * Check if {@link BTS#cellId CID} and {@link BTS#locationAreaCode LAC} is already in {@link BTS} realm
     */
    public boolean cellInDbiBts(Realm realm, int lac, int cellID) {
        long count = realm.where(BTS.class)
                .equalTo("locationAreaCode", lac)
                .equalTo("cellId", cellID)
                .count();

        return count > 0;
    }

    /**
     * Check if {@link BTS#cellId CID} is already in the {@link Measure} realm
     *
     * @param realm The realm to use
     * @param cellId The {@link BTS#cellId cellId} to look for
     * @return true if a {@link Measure} is found with the given cellId
     */
    public boolean cellInDbiMeasure(Realm realm, int cellId) {
        long count = realm.where(Measure.class)
                .equalTo("bts.cellId", cellId)
                .count();

        return count > 0;
    }
}