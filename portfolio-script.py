import requests
from bs4 import BeautifulSoup
import logging
from google.oauth2.service_account import Credentials
from googleapiclient.discovery import build
from google.cloud import secretmanager
import os
import json


class InvestBuxFetcher:
    def __init__(self):
        self.session = requests.Session()
        self.base_url = "https://investbux.themfbox.com" #The name of the website which holds the data to my invested stocks.
        self.login_url = f"{self.base_url}/validateLoginNew"
        self.data_url = f"{self.base_url}/investor"
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "X-Requested-With": "XMLHttpRequest",
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"
        }
        self.session_cookies = {}
        logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
        
    def access_secret(self, secret_name):
        """Fetch the latest version of a secret from Google Secret Manager"""
        client = secretmanager.SecretManagerServiceClient()
        name = f"projects/investbux-script/secrets/{secret_name}/versions/latest"
        response = client.access_secret_version(name=name)
        return response.payload.data.decode("utf-8")

    def login(self, enc_userid: str, enc_pass: str) -> bool:
        logging.info("Attempting to log in.")
        data = {
            "enc_userid": enc_userid,
            "enc_pass": enc_pass,
            "authcode": "",
            "amc": "",
            "scheme": "",
            "amount": "",
            "type": "",
            "risk_profile": ""
        }

        try:
            response = self.session.post(self.login_url, headers=self.headers, data=data)
            response.raise_for_status()

            if response.json().get("status") == 200:
                self.session_cookies = self.session.cookies.get_dict()
                logging.info("Login successful. JSESSIONID saved.")
                return True
            else:
                logging.error("Login failed. Status message: %s", response.json().get("status_msg"))
                return False
        except requests.RequestException as e:
            logging.error(f"Login failed: {e}")
            return False

    def fetch_investor_data(self):
        logging.info("Attempting to fetch investor data.")
        try:
            response = self.session.get(self.data_url, headers=self.headers, cookies=self.session_cookies)
            response.raise_for_status()

            soup = BeautifulSoup(response.text, 'html.parser')
            logging.info("Data fetched successfully.")

            return soup
        except requests.RequestException as e:
            logging.error(f"Failed to fetch data: {e}")
            return None

    def update_google_sheet(self, balance, cell):
        try:
            credentials_json = self.access_secret("credentials-google-sheet")
            credentials = Credentials.from_service_account_info(
                json.loads(credentials_json), scopes=['https://www.googleapis.com/auth/spreadsheets']
            )
            service = build('sheets', 'v4', credentials=credentials)
            sheet = service.spreadsheets()

            values = [[balance]]
            body = {'values': values}

            sheet.values().update(
                spreadsheetId={sheetId},
                range=cell,
                valueInputOption='USER_ENTERED',
                body=body
            ).execute()
            logging.info(f"Balance successfully updated on Google Sheet at {cell}.")
        except Exception as e:
            logging.error(f"Failed to update Google Sheet: {e}")

    def parse_and_update_google_sheet(self, soup):
        logging.info("Parsing fetched data.")
        try:
            # Fetch the 'Total Portfolio Value'
            portfolio_div = soup.find('div', onclick="fnTotalPortfolioValue()")
            portfolio_value = portfolio_div.find('span', class_='comma_fixed').text.strip().replace(',', '')
            portfolio_value = int(float(portfolio_value))
            portfolio_value += 5000

            # Fetch the 'MF Current Cost' by locating the specific div with 'MF Current Cost' text
            mf_cost_div = soup.find('h6', string="MF Current Cost").find_next('h4', class_='d-block l-h-n mt-3 fw-500 totalcost')
            mf_cost_text = mf_cost_div.text.strip().replace('₹', '').replace(',', '')
            mf_cost_value = int(float(mf_cost_text))
            mf_cost_value += 5000

            logging.info(f"Extracted Amounts: Total Portfolio Value = {portfolio_value}, MF Current Cost = {mf_cost_value}")

            # Update values to Google Sheet
            self.update_google_sheet(mf_cost_value, {sheetCell_1})
            self.update_google_sheet(portfolio_value, {sheetCell_2})

        except Exception as e:
            logging.error(f"Error parsing data: {e}")
