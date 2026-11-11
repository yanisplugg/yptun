use std::time::Duration;

use reqwest::blocking::{Client, ClientBuilder};
use reqwest::header::{HeaderName, HeaderValue};
use yptun_core::repository::{HttpFetcher, HttpRequest, HttpResponse};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(35);

pub struct ReqwestFetcher {
    direct: Client,
}

impl Default for ReqwestFetcher {
    fn default() -> Self {
        Self::new()
    }
}

impl ReqwestFetcher {
    pub fn new() -> Self {
        let direct = client_builder().build().expect("reqwest client");
        Self { direct }
    }

    fn client_for(&self, proxy: Option<&str>) -> Option<Client> {
        match proxy.filter(|value| !value.trim().is_empty()) {
            None => Some(self.direct.clone()),
            Some(value) => client_builder()
                .proxy(reqwest::Proxy::all(value).ok()?)
                .build()
                .ok(),
        }
    }
}

fn client_builder() -> ClientBuilder {
    Client::builder()
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        .redirect(reqwest::redirect::Policy::limited(10))
        .tcp_keepalive(Duration::from_secs(30))
}

impl HttpFetcher for ReqwestFetcher {
    fn get(&self, request: &HttpRequest) -> Option<HttpResponse> {
        let client = self.client_for(request.proxy.as_deref())?;
        let mut builder = client.get(&request.url);
        for (name, value) in &request.headers {
            let name = HeaderName::from_bytes(name.as_bytes()).ok()?;
            let value = HeaderValue::from_str(value).ok()?;
            builder = builder.header(name, value);
        }

        let response = builder.send().ok()?;
        let status = response.status().as_u16();
        let mut headers = Vec::new();
        for name in response.headers().keys() {
            for value in response.headers().get_all(name) {
                headers.push((
                    name.as_str().to_string(),
                    value.to_str().unwrap_or_default().to_string(),
                ));
            }
        }
        let body = response.text().ok()?;
        Some(HttpResponse {
            status,
            headers,
            body,
        })
    }
}
