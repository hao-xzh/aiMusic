# Render the two __PIPO_*__ values before installing this private VPC origin.
limit_req_zone $binary_remote_addr zone=pipo_requests:1m rate=10r/s;
limit_conn_zone $binary_remote_addr zone=pipo_clients:1m;

server {
    listen __PIPO_PRIVATE_IP__:8081;
    server_name _;
    server_tokens off;
    if ($http_x_origin_site != "__PIPO_ORIGIN_SECRET__") { return 444; }

    # The dedicated security group permits only CloudFront's VPC origin ENIs.
    set_real_ip_from 172.31.0.0/16;
    real_ip_header X-Forwarded-For;
    real_ip_recursive off;

    # The associated CloudFront viewer-request function verifies device cookies.
    # The origin secret and dedicated VPC security group prevent bypassing it.
    limit_req zone=pipo_requests burst=50 nodelay;
    limit_req_status 429;
    limit_conn pipo_clients 20;
    client_max_body_size 2m;
    client_body_timeout 15s;
    send_timeout 60s;

    access_log /var/log/nginx/pipo.access.log;
    error_log /var/log/nginx/pipo.error.log warn;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options DENY always;
    add_header Referrer-Policy same-origin always;
    add_header X-Robots-Tag "noindex, nofollow, noarchive" always;

    location = / { return 302 /pwa; }
    location = /pwa/probe { return 404; }
    location ~ ^/(?:pwa(?:/|$)|pwa-sw\.js$|_next/|api/pwa/|icon\.png$|apple-icon\.png$|favicon\.ico$) {
        proxy_pass http://127.0.0.1:4331;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Authorization "";
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_connect_timeout 5s;
        proxy_read_timeout 180s;
        proxy_hide_header X-Powered-By;
        proxy_hide_header X-Pipo-Session;
    }
    location / { return 404; }
}
