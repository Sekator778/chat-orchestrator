FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY index_repo.py search.py ./

CMD ["python", "index_repo.py"]
