FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm test && npm run build
ENV NODE_ENV=production PORT=3000
EXPOSE 3000
CMD ["npm", "start"]
