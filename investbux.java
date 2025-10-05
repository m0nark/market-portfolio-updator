

package com.yourpackage;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.gson.Gson;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.*;

@Service
public class InvestBuxFetcher {
    
    private static final Logger logger = LoggerFactory.getLogger(InvestBuxFetcher.class);
    
    private final String baseUrl = "https://investbux.themfbox.com";
    private final String loginUrl = baseUrl + "/validateLoginNew";
    private final String dataUrl = baseUrl + "/investor";
    
    @Value("${google.project.id:investbux-script}")
    private String projectId;
    
    @Value("${google.sheet.id}")
    private String sheetId;
    
    @Value("${google.sheet.cell1}")
    private String sheetCell1;
    
    @Value("${google.sheet.cell2}")
    private String sheetCell2;
    
    @Value("${investbux.enc.userid}")
    private String encUserId;
    
    @Value("${investbux.enc.pass}")
    private String encPass;
    
    private Map<String, String> sessionCookies = new HashMap<>();
    

    
    /**
     * Login to the InvestBux platform
     */
    public boolean login(String encUserId, String encPass) {
        logger.info("Attempting to log in.");
        
        try {
            Connection.Response response = Jsoup.connect(loginUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .data("enc_userid", encUserId)
                    .data("enc_pass", encPass)
                    .data("authcode", "")
                    .data("amc", "")
                    .data("scheme", "")
                    .data("amount", "")
                    .data("type", "")
                    .data("risk_profile", "")
                    .method(Connection.Method.POST)
                    .execute();
            
            // Parse JSON response
            String responseBody = response.body();
            Gson gson = new Gson();
            Map<String, Object> jsonResponse = gson.fromJson(responseBody, Map.class);
            
            Double status = (Double) jsonResponse.get("status");
            if (status != null && status.intValue() == 200) {
                sessionCookies = response.cookies();
                logger.info("Login successful. Session cookies saved.");
                return true;
            } else {
                logger.error("Login failed. Status message: {}", jsonResponse.get("status_msg"));
                return false;
            }
            
        } catch (IOException e) {
            logger.error("Login failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Fetch investor data from the platform
     */
    public Document fetchInvestorData() {
        logger.info("Attempting to fetch investor data.");
        
        try {
            Connection connection = Jsoup.connect(dataUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36");
            
            // Add session cookies
            for (Map.Entry<String, String> cookie : sessionCookies.entrySet()) {
                connection.cookie(cookie.getKey(), cookie.getValue());
            }
            
            Document document = connection.get();
            logger.info("Data fetched successfully.");
            return document;
            
        } catch (IOException e) {
            logger.error("Failed to fetch data: {}", e.getMessage());
            return null;
        }
    }
    
    public void updateGoogleSheet(int balance, String cell) {
        try {
            // You'll need to store your Google service account credentials JSON 
            // in application.properties as a string or file path
            String credentialsJson = "YOUR_GOOGLE_CREDENTIALS_JSON"; // Replace with actual implementation
            
            ServiceAccountCredentials credentials = ServiceAccountCredentials
                    .fromStream(new ByteArrayInputStream(credentialsJson.getBytes()))
                    .createScoped(Collections.singletonList("https://www.googleapis.com/auth/spreadsheets"));
            
            Sheets service = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("InvestBux Fetcher")
                    .build();
            
            List<List<Object>> values = Arrays.asList(
                    Arrays.asList(balance)
            );
            
            ValueRange body = new ValueRange().setValues(values);
            
            service.spreadsheets().values()
                    .update(sheetId, cell, body)
                    .setValueInputOption("USER_ENTERED")
                    .execute();
            
            logger.info("Balance successfully updated on Google Sheet at {}", cell);
            
        } catch (IOException | GeneralSecurityException e) {
            logger.error("Failed to update Google Sheet: {}", e.getMessage());
        }
    }
    
    /**
     * Parse the fetched data and update Google Sheet
     */
    public void parseAndUpdateGoogleSheet(Document document) {
        logger.info("Parsing fetched data.");
        
        try {
            // Fetch the 'Total Portfolio Value'
            Element portfolioDiv = document.selectFirst("div[onclick*=fnTotalPortfolioValue]");
            if (portfolioDiv == null) {
                throw new RuntimeException("Portfolio div not found");
            }
            
            Element portfolioSpan = portfolioDiv.selectFirst("span.comma_fixed");
            if (portfolioSpan == null) {
                throw new RuntimeException("Portfolio value span not found");
            }
            
            String portfolioText = portfolioSpan.text().trim().replace(",", "");
            int portfolioValue = (int) Double.parseDouble(portfolioText) + 5000;
            
            // Fetch the 'MF Current Cost'
            Element mfCostHeader = document.selectFirst("h6:contains(MF Current Cost)");
            if (mfCostHeader == null) {
                throw new RuntimeException("MF Current Cost header not found");
            }
            
            Element mfCostElement = mfCostHeader.nextElementSibling();
            while (mfCostElement != null && !mfCostElement.hasClass("totalcost")) {
                mfCostElement = mfCostElement.nextElementSibling();
            }
            
            if (mfCostElement == null) {
                throw new RuntimeException("MF Current Cost value not found");
            }
            
            String mfCostText = mfCostElement.text().trim()
                    .replace("₹", "")
                    .replace(",", "");
            int mfCostValue = (int) Double.parseDouble(mfCostText) + 5000;
            
            logger.info("Extracted Amounts: Total Portfolio Value = {}, MF Current Cost = {}", 
                       portfolioValue, mfCostValue);
            
            // Update values to Google Sheet
            updateGoogleSheet(mfCostValue, sheetCell1);
            updateGoogleSheet(portfolioValue, sheetCell2);
            
        } catch (Exception e) {
            logger.error("Error parsing data: {}", e.getMessage());
        }
    }
    
    /**
     * Main method to execute the complete flow
     */
    public void executeDataFetch() {
        try {
            if (login(encUserId, encPass)) {
                Document document = fetchInvestorData();
                if (document != null) {
                    parseAndUpdateGoogleSheet(document);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to execute data fetch: {}", e.getMessage());
        }
    }
}

// Controller class - Add this to your existing controllers package
package com.yourpackage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/investbux")
public class InvestBuxController {
    
    @Autowired
    private InvestBuxFetcher investBuxFetcher;
    
    @GetMapping("/fetch-data")
    public ResponseEntity<String> fetchData() {
        try {
            investBuxFetcher.executeDataFetch();
            return ResponseEntity.ok("Data fetch completed successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Error occurred during data fetch: " + e.getMessage());
        }
    }
}
